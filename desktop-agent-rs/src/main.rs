mod capture;
mod config;
#[cfg(windows)]
mod gui;
mod input;
mod log;
mod protocol;

use std::{thread, time::Duration};

use anyhow::{Context, Result};
use capture::CaptureState;
use config::AgentConfig;
use futures_util::{SinkExt, StreamExt};
use parking_lot::Mutex;
use protocol::{BinaryFrameHeader, ClientEvent, DeviceStatus, ServerEvent};
use std::sync::Arc;
use tokio::sync::mpsc;
use tokio_tungstenite::{connect_async, tungstenite::Message};

const AGENT_VERSION: &str = "0.2.3-rs";
const FRAME_INTERVAL_IDLE: Duration = Duration::from_millis(80);
const FRAME_INTERVAL_FAST: Duration = Duration::from_millis(25);
const FAST_FRAME_MS: u64 = 900;
const BINARY_FRAME_MAGIC: &[u8; 5] = b"BHZF1";

enum OutboundEvent {
    Json(ClientEvent),
    Binary(capture::CaptureFrame),
}

#[tokio::main]
async fn main() -> Result<()> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let args: Vec<String> = std::env::args().collect();
    let show_id = args.iter().any(|item| item == "--show-id");
    let config_path = arg_value(&args, "--config");
    let config = AgentConfig::load(config_path.as_deref())?;
    log::init(&config);
    if show_id {
        println!("BHZN ToDesk Rust Agent {}", AGENT_VERSION);
        println!("设备 ID: {}", config.device_id);
        println!("验证码: {}", config.verification_code);
        println!("服务器: {}", config.server);
        println!("配置文件: {}", config.path.display());
        return Ok(());
    }

    if cfg!(windows) && !args.iter().any(|item| item == "--headless") {
        hide_console_window();
        return run_gui(config);
    }

    run_forever(config).await
}

#[cfg(windows)]
fn run_gui(config: AgentConfig) -> Result<()> {
    let worker_config = config.clone();
    thread::spawn(move || {
        let runtime = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
            Ok(runtime) => runtime,
            Err(error) => {
                eprintln!("[agent-rs] runtime failed: {error:#}");
                return;
            }
        };
        if let Err(error) = runtime.block_on(run_forever(worker_config)) {
            eprintln!("[agent-rs] stopped: {error:#}");
        }
    });
    gui::run(config)
}

#[cfg(not(windows))]
fn run_gui(_config: AgentConfig) -> Result<()> {
    anyhow::bail!("gui is only implemented on Windows in this clean-room agent version")
}

async fn run_forever(config: AgentConfig) -> Result<()> {
    let mut delay = Duration::from_millis(1500);
    loop {
        match run_once(config.clone()).await {
            Ok(()) => delay = Duration::from_millis(1500),
            Err(error) => {
                log::warn(format!("disconnected: {error:#}"));
                eprintln!("[agent-rs] disconnected: {error:#}");
            }
        }
        tokio::time::sleep(delay).await;
        delay = (delay * 2).min(Duration::from_secs(30));
    }
}

async fn run_once(config: AgentConfig) -> Result<()> {
    let ws_url = config.ws_url()?;
    log::info(format!("connecting {}", ws_url));
    eprintln!("[agent-rs] connecting {ws_url}");
    let (ws, _) = connect_async(ws_url.as_str()).await.context("connect websocket")?;
    log::info("connected websocket");
    eprintln!("[agent-rs] connected");

    let (mut writer, mut reader) = ws.split();
    let (out_tx, mut out_rx) = mpsc::unbounded_channel::<OutboundEvent>();
    let capture = Arc::new(Mutex::new(CaptureState::new()));
    let fast_until = Arc::new(std::sync::atomic::AtomicU64::new(0));

    out_tx.send(OutboundEvent::Json(ClientEvent::HelloDevice(DeviceStatus::from_config(&config, AGENT_VERSION, &capture.lock()))))?;
    log::info(format!("hello-device sent id={}", config.device_id));

    let write_task = tokio::spawn(async move {
        while let Some(event) = out_rx.recv().await {
            match event {
                OutboundEvent::Json(event) => {
                    let text = serde_json::to_string(&event)?;
                    writer.send(Message::Text(text.into())).await?;
                }
                OutboundEvent::Binary(frame) => {
                    writer.send(Message::Binary(encode_binary_frame(frame)?.into())).await?;
                }
            }
        }
        anyhow::Ok(())
    });

    let heartbeat_tx = out_tx.clone();
    let heartbeat_config = config.clone();
    let heartbeat_capture = capture.clone();
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(Duration::from_secs(15));
        loop {
            tick.tick().await;
            let status = DeviceStatus::from_config(&heartbeat_config, AGENT_VERSION, &heartbeat_capture.lock());
            if heartbeat_tx.send(OutboundEvent::Json(ClientEvent::Heartbeat(status))).is_err() {
                break;
            }
        }
    });

    let frame_tx = out_tx.clone();
    let frame_capture = capture.clone();
    let frame_fast_until = fast_until.clone();
    tokio::spawn(async move {
        let mut sent_frames: u64 = 0;
        loop {
            let fast = now_ms() < frame_fast_until.load(std::sync::atomic::Ordering::Relaxed);
            let result = {
                let mut guard = frame_capture.lock();
                guard.capture_frame(fast)
            };
            match result {
                Ok(frame) => {
                    sent_frames += 1;
                    if sent_frames == 1 || sent_frames % 300 == 0 {
                        log::info(format!(
                            "frame sent count={} bytes={} size={}x{} input={}x{} fast={}",
                            sent_frames,
                            frame.image.len(),
                            frame.width,
                            frame.height,
                            frame.input_width,
                            frame.input_height,
                            fast
                        ));
                    }
                    if frame_tx.send(OutboundEvent::Binary(frame)).is_err() {
                        break;
                    }
                }
                Err(error) => {
                    log::warn(format!("capture failed: {error:#}"));
                    eprintln!("[agent-rs] capture failed: {error:#}");
                }
            }
            tokio::time::sleep(if fast { FRAME_INTERVAL_FAST } else { FRAME_INTERVAL_IDLE }).await;
        }
    });

    while let Some(raw) = reader.next().await {
        let raw = raw.context("read websocket")?;
        if !raw.is_text() {
            continue;
        }
        let event: ServerEvent = serde_json::from_str(raw.to_text()?).context("parse server event")?;
        match event {
            ServerEvent::Input(input_event) => {
                fast_until.store(now_ms() + FAST_FRAME_MS, std::sync::atomic::Ordering::Relaxed);
                log::info(format!("input action={}", input_event.action));
                if let Err(error) = input::handle_input(input_event, &capture.lock()) {
                    log::warn(format!("input failed: {error:#}"));
                    eprintln!("[agent-rs] input failed: {error:#}");
                }
            }
            ServerEvent::ServerReplaced => {
                log::warn("server replaced this device connection");
                break;
            }
            ServerEvent::StopControl { .. } => log::info("stop-control received"),
            ServerEvent::ControlRequest { .. } => log::info("control-request received"),
            ServerEvent::Hello { .. } => log::info("hello received from server"),
            ServerEvent::Other => {}
        }
    }

    log::warn("websocket reader ended");
    write_task.abort();
    Ok(())
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn encode_binary_frame(frame: capture::CaptureFrame) -> Result<Vec<u8>> {
    let header = BinaryFrameHeader {
        frame_id: frame.frame_id,
        frame_kind: frame.frame_kind,
        width: frame.width,
        height: frame.height,
        input_width: frame.input_width,
        input_height: frame.input_height,
        timestamp: frame.timestamp,
    };
    let header = serde_json::to_vec(&header)?;
    if header.len() > u32::MAX as usize {
        anyhow::bail!("frame header too large");
    }
    let mut out = Vec::with_capacity(BINARY_FRAME_MAGIC.len() + 4 + header.len() + frame.image.len());
    out.extend_from_slice(BINARY_FRAME_MAGIC);
    out.extend_from_slice(&(header.len() as u32).to_le_bytes());
    out.extend_from_slice(&header);
    out.extend_from_slice(&frame.image);
    Ok(out)
}

fn arg_value(args: &[String], name: &str) -> Option<String> {
    args.windows(2)
        .find(|pair| pair[0] == name)
        .map(|pair| pair[1].clone())
}

#[cfg(windows)]
fn hide_console_window() {
    use windows::Win32::System::Console::GetConsoleWindow;
    use windows::Win32::UI::WindowsAndMessaging::{ShowWindow, SW_HIDE};

    unsafe {
        let hwnd = GetConsoleWindow();
        if !hwnd.0.is_null() {
            let _ = ShowWindow(hwnd, SW_HIDE);
        }
    }
}

#[cfg(not(windows))]
fn hide_console_window() {}
