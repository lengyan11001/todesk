use std::{collections::HashMap, sync::Arc, time::Duration};

use anyhow::{Context, Result};
use bytes::Bytes;
use parking_lot::Mutex;
use serde_json::json;
use tokio::sync::mpsc;
use webrtc::{
    api::{media_engine::MediaEngine, APIBuilder},
    data_channel::{data_channel_message::DataChannelMessage, RTCDataChannel},
    ice_transport::{ice_candidate::RTCIceCandidateInit, ice_server::RTCIceServer},
    peer_connection::{
        configuration::RTCConfiguration,
        peer_connection_state::RTCPeerConnectionState,
        sdp::session_description::RTCSessionDescription,
        RTCPeerConnection,
    },
};

use crate::{
    capture::{CaptureFrame, CaptureState},
    input,
    log,
    protocol::{
        ClientEvent, InputEvent, RtcIceCandidate, RtcIceCandidateSignal, RtcIceServer as ProtocolIceServer, RtcSdp, RtcState, RtcStop,
    },
};

const RTC_FRAME_INTERVAL: Duration = Duration::from_millis(66);
const RTC_FAST_FRAME_MS: u64 = 900;
const RTC_BINARY_FRAME_MAGIC: &[u8; 5] = b"BHZF1";

pub enum RtcOutbound {
    Json(ClientEvent),
}

pub struct RtcManager {
    device_id: String,
    capture: Arc<Mutex<CaptureState>>,
    out_tx: mpsc::UnboundedSender<RtcOutbound>,
    sessions: Mutex<HashMap<String, Arc<RtcSession>>>,
}

struct RtcSession {
    session_id: String,
    pc: RTCPeerConnection,
    control_channel: Mutex<Option<Arc<RTCDataChannel>>>,
    frame_channel: Mutex<Option<Arc<RTCDataChannel>>>,
    fast_until: std::sync::atomic::AtomicU64,
}

impl RtcManager {
    pub fn new(device_id: String, capture: Arc<Mutex<CaptureState>>, out_tx: mpsc::UnboundedSender<RtcOutbound>) -> Arc<Self> {
        Arc::new(Self {
            device_id,
            capture,
            out_tx,
            sessions: Mutex::new(HashMap::new()),
        })
    }

    pub async fn start_session(self: &Arc<Self>, session_id: String, ice_servers: Vec<ProtocolIceServer>) {
        if session_id.is_empty() {
            return;
        }
        self.close_session(&session_id).await;
        match self.build_session(&session_id, ice_servers).await {
            Ok(session) => {
                self.sessions.lock().insert(session_id.clone(), session);
                self.send_state(&session_id, "checking", "unknown", "");
            }
            Err(error) => {
                log::warn(format!("rtc start failed session={session_id}: {error:#}"));
                self.send_state(&session_id, "failed", "unknown", &format!("{error:#}"));
                self.send_stop(&session_id, "start_failed");
            }
        }
    }

    pub async fn handle_offer(self: &Arc<Self>, session_id: String, sdp: String) {
        let session = self.sessions.lock().get(&session_id).cloned();
        let Some(session) = session else {
            self.send_state(&session_id, "failed", "unknown", "bad_rtc_session");
            return;
        };
        if sdp.trim().is_empty() {
            self.send_state(&session_id, "failed", "unknown", "empty_offer");
            return;
        }
        if let Err(error) = self.apply_offer(session, sdp).await {
            log::warn(format!("rtc offer failed session={session_id}: {error:#}"));
            self.send_state(&session_id, "failed", "unknown", &format!("{error:#}"));
            self.close_session(&session_id).await;
        }
    }

    pub async fn handle_candidate(self: &Arc<Self>, session_id: String, candidate: Option<RtcIceCandidate>) {
        let Some(candidate) = candidate else {
            return;
        };
        let session = self.sessions.lock().get(&session_id).cloned();
        let Some(session) = session else {
            return;
        };
        let init = RTCIceCandidateInit {
            candidate: candidate.candidate,
            sdp_mid: candidate.sdp_mid,
            sdp_mline_index: candidate.sdp_mline_index,
            username_fragment: candidate.username_fragment,
        };
        if let Err(error) = session.pc.add_ice_candidate(init).await {
            log::warn(format!("rtc add candidate failed session={session_id}: {error:#}"));
        }
    }

    pub async fn close_session(self: &Arc<Self>, session_id: &str) {
        let session = self.sessions.lock().remove(session_id);
        if let Some(session) = session {
            log::info(format!("rtc closing session={}", session.session_id));
            let _ = session.pc.close().await;
        }
    }

    async fn build_session(self: &Arc<Self>, session_id: &str, ice_servers: Vec<ProtocolIceServer>) -> Result<Arc<RtcSession>> {
        let mut media_engine = MediaEngine::default();
        media_engine.register_default_codecs()?;
        let api = APIBuilder::new().with_media_engine(media_engine).build();
        let pc = api
            .new_peer_connection(RTCConfiguration { ice_servers: convert_ice_servers(ice_servers), ..Default::default() })
            .await?;
        let session = Arc::new(RtcSession {
            session_id: session_id.to_string(),
            pc,
            control_channel: Mutex::new(None),
            frame_channel: Mutex::new(None),
            fast_until: std::sync::atomic::AtomicU64::new(0),
        });

        let manager = Arc::clone(self);
        let state_session_id = session_id.to_string();
        session.pc.on_peer_connection_state_change(Box::new(move |state: RTCPeerConnectionState| {
            let manager = Arc::clone(&manager);
            let session_id = state_session_id.clone();
            Box::pin(async move {
                let state_text = match state {
                    RTCPeerConnectionState::New => "new",
                    RTCPeerConnectionState::Connecting => "checking",
                    RTCPeerConnectionState::Connected => "connected",
                    RTCPeerConnectionState::Disconnected => "disconnected",
                    RTCPeerConnectionState::Failed => "failed",
                    RTCPeerConnectionState::Closed => "closed",
                    _ => "unknown",
                };
                manager.send_state(&session_id, state_text, "unknown", "");
                if matches!(state, RTCPeerConnectionState::Failed | RTCPeerConnectionState::Closed) {
                    manager.close_session(&session_id).await;
                }
            })
        }));

        let manager = Arc::clone(self);
        let ice_session_id = session_id.to_string();
        session.pc.on_ice_candidate(Box::new(move |candidate| {
            let manager = Arc::clone(&manager);
            let session_id = ice_session_id.clone();
            Box::pin(async move {
                let Some(candidate) = candidate else {
                    return;
                };
                match candidate.to_json() {
                    Ok(value) => manager.send_candidate(&session_id, value),
                    Err(error) => log::warn(format!("rtc candidate serialize failed session={session_id}: {error:#}")),
                }
            })
        }));

        let manager = Arc::clone(self);
        let data_session = Arc::clone(&session);
        session.pc.on_data_channel(Box::new(move |channel: Arc<RTCDataChannel>| {
            let manager = Arc::clone(&manager);
            let session = Arc::clone(&data_session);
            Box::pin(async move {
                let label = channel.label().to_string();
                if label == "control" {
                    manager.attach_control_channel(Arc::clone(&session), channel);
                } else if label == "frames" {
                    manager.attach_frame_channel(Arc::clone(&session), channel);
                }
            })
        }));

        Ok(session)
    }

    async fn apply_offer(self: &Arc<Self>, session: Arc<RtcSession>, sdp: String) -> Result<()> {
        session.pc.set_remote_description(RTCSessionDescription::offer(sdp)?).await?;
        let answer = session.pc.create_answer(None).await?;
        session.pc.set_local_description(answer).await?;
        let local = session.pc.local_description().await.context("missing local description")?;
        self.out_tx
            .send(RtcOutbound::Json(ClientEvent::RtcAnswer(RtcSdp {
                session_id: session.session_id.clone(),
                device_id: self.device_id.clone(),
                sdp: local.sdp,
            })))
            .ok();
        Ok(())
    }

    fn attach_control_channel(self: &Arc<Self>, session: Arc<RtcSession>, channel: Arc<RTCDataChannel>) {
        session.control_channel.lock().replace(Arc::clone(&channel));
        let manager = Arc::clone(self);
        channel.on_message(Box::new(move |message: DataChannelMessage| {
            let manager = Arc::clone(&manager);
            let session = Arc::clone(&session);
            Box::pin(async move {
                if !message.is_string {
                    return;
                }
                let text = String::from_utf8_lossy(&message.data).to_string();
                manager.handle_control_message(session, text).await;
            })
        }));
    }

    fn attach_frame_channel(self: &Arc<Self>, session: Arc<RtcSession>, channel: Arc<RTCDataChannel>) {
        session.frame_channel.lock().replace(Arc::clone(&channel));
        let manager = Arc::clone(self);
        let open_session = Arc::clone(&session);
        channel.on_open(Box::new(move || {
            let manager = Arc::clone(&manager);
            let session = Arc::clone(&open_session);
            Box::pin(async move {
                log::info(format!("rtc frame channel open session={}", session.session_id));
                manager.spawn_frame_loop(session);
            })
        }));
    }

    async fn handle_control_message(self: &Arc<Self>, session: Arc<RtcSession>, text: String) {
        let mut value = match serde_json::from_str::<serde_json::Value>(&text) {
            Ok(value) => value,
            Err(_) => return,
        };
        if value.get("type").and_then(|item| item.as_str()) != Some("input") {
            return;
        }
        if let Some(object) = value.as_object_mut() {
            object.insert("sessionId".to_string(), serde_json::Value::String(session.session_id.clone()));
        }
        let input_id = value.get("inputId").and_then(|item| item.as_str()).unwrap_or("").to_string();
        let action = value.get("action").and_then(|item| item.as_str()).unwrap_or("").to_string();
        let input: InputEvent = match serde_json::from_value(value) {
            Ok(input) => input,
            Err(error) => {
                self.send_control_result(&session, &input_id, &action, false, &format!("{error:#}")).await;
                return;
            }
        };
        session.fast_until.store(now_ms() + RTC_FAST_FRAME_MS, std::sync::atomic::Ordering::Relaxed);
        let result = {
            let capture = self.capture.lock();
            input::handle_input(input, &capture)
        };
        match result {
            Ok(()) => self.send_control_result(&session, &input_id, &action, true, "").await,
            Err(error) => self.send_control_result(&session, &input_id, &action, false, &format!("{error:#}")).await,
        }
    }

    async fn send_control_result(&self, session: &RtcSession, input_id: &str, action: &str, ok: bool, error: &str) {
        let channel = session.control_channel.lock().clone();
        if let Some(channel) = channel {
            let payload = json!({
                "type": "input-result",
                "sessionId": session.session_id,
                "inputId": input_id,
                "action": action,
                "ok": ok,
                "error": error.chars().take(400).collect::<String>(),
            });
            let _ = channel.send_text(payload.to_string()).await;
        }
    }

    fn spawn_frame_loop(self: &Arc<Self>, session: Arc<RtcSession>) {
        let manager = Arc::clone(self);
        tokio::spawn(async move {
            let mut sent: u64 = 0;
            loop {
                let channel = session.frame_channel.lock().clone();
                let Some(channel) = channel else {
                    break;
                };
                let fast = now_ms() < session.fast_until.load(std::sync::atomic::Ordering::Relaxed);
                let frame = {
                    let mut capture = manager.capture.lock();
                    capture.capture_frame(fast)
                };
                match frame {
                    Ok(frame) => {
                        sent += 1;
                        match encode_rtc_binary_frame(&manager.device_id, frame) {
                            Ok(payload) => {
                                if channel.send(&Bytes::from(payload)).await.is_err() {
                                    break;
                                }
                                if sent == 1 || sent % 300 == 0 {
                                    log::info(format!("rtc frame sent session={} count={sent}", session.session_id));
                                }
                            }
                            Err(error) => log::warn(format!("rtc encode frame failed session={}: {error:#}", session.session_id)),
                        }
                    }
                    Err(error) => log::warn(format!("rtc capture failed session={}: {error:#}", session.session_id)),
                }
                tokio::time::sleep(if fast { Duration::from_millis(33) } else { RTC_FRAME_INTERVAL }).await;
            }
            manager.close_session(&session.session_id).await;
        });
    }

    fn send_candidate(&self, session_id: &str, candidate: RTCIceCandidateInit) {
        self.out_tx
            .send(RtcOutbound::Json(ClientEvent::RtcIceCandidate(RtcIceCandidateSignal {
                session_id: session_id.to_string(),
                device_id: self.device_id.clone(),
                candidate: RtcIceCandidate {
                    candidate: candidate.candidate,
                    sdp_mid: candidate.sdp_mid,
                    sdp_mline_index: candidate.sdp_mline_index,
                    username_fragment: candidate.username_fragment,
                },
            })))
            .ok();
        self.send_state(session_id, "checking", "unknown", "");
    }

    fn send_state(&self, session_id: &str, state: &str, candidate_type: &str, error: &str) {
        self.out_tx
            .send(RtcOutbound::Json(ClientEvent::RtcState(RtcState {
                session_id: session_id.to_string(),
                device_id: self.device_id.clone(),
                state: state.to_string(),
                selected_candidate_type: candidate_type.to_string(),
                rtt_ms: 0,
                bitrate_kbps: 0,
                error: error.chars().take(400).collect(),
            })))
            .ok();
    }

    fn send_stop(&self, session_id: &str, reason: &str) {
        self.out_tx
            .send(RtcOutbound::Json(ClientEvent::RtcStop(RtcStop::new(&self.device_id, session_id, reason))))
            .ok();
    }
}

fn convert_ice_servers(values: Vec<ProtocolIceServer>) -> Vec<RTCIceServer> {
    values
        .into_iter()
        .filter_map(|item| {
            let urls: Vec<String> = item.urls.into_vec().into_iter().filter(|url| !url.trim().is_empty()).collect();
            if urls.is_empty() {
                return None;
            }
            Some(RTCIceServer {
                urls,
                username: item.username.unwrap_or_default(),
                credential: item.credential.unwrap_or_default(),
                ..Default::default()
            })
        })
        .collect()
}

fn encode_rtc_binary_frame(device_id: &str, frame: CaptureFrame) -> Result<Vec<u8>> {
    let header = json!({
        "type": "frame",
        "deviceId": device_id,
        "frameId": frame.frame_id,
        "frameKind": frame.frame_kind,
        "width": frame.width,
        "height": frame.height,
        "inputWidth": frame.input_width,
        "inputHeight": frame.input_height,
        "timestamp": frame.timestamp,
        "transport": "rtc-datachannel"
    });
    let header = serde_json::to_vec(&header)?;
    if header.len() > u32::MAX as usize {
        anyhow::bail!("rtc frame header too large");
    }
    let mut out = Vec::with_capacity(RTC_BINARY_FRAME_MAGIC.len() + 4 + header.len() + frame.image.len());
    out.extend_from_slice(RTC_BINARY_FRAME_MAGIC);
    out.extend_from_slice(&(header.len() as u32).to_le_bytes());
    out.extend_from_slice(&header);
    out.extend_from_slice(&frame.image);
    Ok(out)
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
