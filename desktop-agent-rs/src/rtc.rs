use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc,
    },
    time::Duration,
};

use anyhow::Result;
use bytes::Bytes;
use openh264::{encoder::{BitRate, Complexity, Encoder, EncoderConfig, FrameRate, UsageType}, formats::{BgraSliceU8, YUVBuffer}};
use parking_lot::Mutex;
use serde_json::json;
use tokio::sync::mpsc;
use webrtc::{
    api::{media_engine::{MediaEngine, MIME_TYPE_H264}, APIBuilder},
    data_channel::{data_channel_message::DataChannelMessage, RTCDataChannel},
    ice_transport::{ice_candidate::RTCIceCandidateInit, ice_server::RTCIceServer},
    peer_connection::{
        configuration::RTCConfiguration,
        peer_connection_state::RTCPeerConnectionState,
        sdp::session_description::RTCSessionDescription,
        RTCPeerConnection,
    },
    track::track_local::track_local_static_sample::TrackLocalStaticSample,
    rtp_transceiver::rtp_codec::RTCRtpCodecCapability,
};

use crate::{
    capture::{CaptureOptions, CaptureState},
    input,
    log,
    protocol::{
        ClientEvent, InputEvent, QualityProfile, RtcIceCandidate, RtcIceCandidateSignal, RtcIceServer as ProtocolIceServer, RtcSdp, RtcState,
        RtcStop,
    },
};

const RTC_FAST_FRAME_MS: u64 = 900;
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
    video_track: Arc<TrackLocalStaticSample>,
    video_started: AtomicBool,
    quality: QualityProfile,
    fast_until: AtomicU64,
    frames_captured: AtomicU64,
    frames_encoded: AtomicU64,
    bytes_written: AtomicU64,
    write_errors: AtomicU64,
    last_error: Mutex<String>,
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

    pub async fn start_session(self: &Arc<Self>, session_id: String, ice_servers: Vec<ProtocolIceServer>, quality: QualityProfile) {
        if session_id.is_empty() {
            return;
        }
        self.close_session(&session_id).await;
        let quality = quality.sanitize_with(&QualityProfile::balanced());
        match self.build_session(&session_id, ice_servers, quality).await {
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

    async fn build_session(
        self: &Arc<Self>,
        session_id: &str,
        ice_servers: Vec<ProtocolIceServer>,
        quality: QualityProfile,
    ) -> Result<Arc<RtcSession>> {
        let mut media_engine = MediaEngine::default();
        media_engine.register_default_codecs()?;
        let api = APIBuilder::new().with_media_engine(media_engine).build();
        let pc = api
            .new_peer_connection(RTCConfiguration { ice_servers: convert_ice_servers(ice_servers), ..Default::default() })
            .await?;
        let video_track = Arc::new(TrackLocalStaticSample::new(
            RTCRtpCodecCapability {
                mime_type: MIME_TYPE_H264.to_string(),
                clock_rate: 90_000,
                sdp_fmtp_line: "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42001f".to_string(),
                ..Default::default()
            },
            "screen".to_string(),
            "bhzn".to_string(),
        ));
        let _ = pc
            .add_track(Arc::clone(&video_track) as Arc<dyn webrtc::track::track_local::TrackLocal + Send + Sync>)
            .await?;
        let session = Arc::new(RtcSession {
            session_id: session_id.to_string(),
            pc,
            control_channel: Mutex::new(None),
            video_track,
            video_started: AtomicBool::new(false),
            quality,
            fast_until: AtomicU64::new(0),
            frames_captured: AtomicU64::new(0),
            frames_encoded: AtomicU64::new(0),
            bytes_written: AtomicU64::new(0),
            write_errors: AtomicU64::new(0),
            last_error: Mutex::new(String::new()),
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
                } else if label == "pointer" {
                    manager.attach_pointer_channel(Arc::clone(&session), channel);
                }
            })
        }));

        Ok(session)
    }

    async fn apply_offer(self: &Arc<Self>, session: Arc<RtcSession>, sdp: String) -> Result<()> {
        session.pc.set_remote_description(RTCSessionDescription::offer(sdp)?).await?;
        let mut tx_info = String::new();
        let mut video_tx: Option<Arc<webrtc::rtp_transceiver::RTCRtpTransceiver>> = None;
        for transceiver in session.pc.get_transceivers().await {
            let mid = transceiver.mid().map(|value| value.to_string()).unwrap_or_default();
            let has_track = transceiver.sender().await.track().await.is_some();
            tx_info.push_str(&format!(
                "mid={mid},kind={:?},dir={:?},track={has_track};",
                transceiver.kind(),
                transceiver.direction()
            ));
            if transceiver.kind() == webrtc::rtp_transceiver::rtp_codec::RTPCodecType::Video && video_tx.is_none() {
                video_tx = Some(transceiver);
            }
        }
        if let Some(transceiver) = video_tx {
            transceiver
                .set_direction(webrtc::rtp_transceiver::rtp_transceiver_direction::RTCRtpTransceiverDirection::Sendonly)
                .await;
            tx_info.push_str("sendonly_applied;");
        }
        let answer = session.pc.create_answer(None).await?;
        let merged_sdp = merge_answer_video_sdp(&answer.sdp);
        let answer = RTCSessionDescription::answer(merged_sdp.clone())?;
        session.pc.set_local_description(answer).await?;
        self.send_state(&session.session_id, "negotiated", "unknown", &tx_info);
        self.out_tx
            .send(RtcOutbound::Json(ClientEvent::RtcAnswer(RtcSdp {
                session_id: session.session_id.clone(),
                device_id: self.device_id.clone(),
                sdp: merged_sdp,
            })))
            .ok();
        self.spawn_frame_loop(session);
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

    fn attach_pointer_channel(self: &Arc<Self>, session: Arc<RtcSession>, channel: Arc<RTCDataChannel>) {
        let manager = Arc::clone(self);
        channel.on_message(Box::new(move |message: DataChannelMessage| {
            let manager = Arc::clone(&manager);
            let session = Arc::clone(&session);
            Box::pin(async move {
                if !message.is_string {
                    return;
                }
                let text = String::from_utf8_lossy(&message.data).to_string();
                manager.handle_pointer_message(session, text).await;
            })
        }));
    }

    async fn handle_pointer_message(self: &Arc<Self>, session: Arc<RtcSession>, text: String) {
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
        let input: InputEvent = match serde_json::from_value(value) {
            Ok(input) => input,
            Err(_) => return,
        };
        session.fast_until.store(now_ms() + RTC_FAST_FRAME_MS, Ordering::Relaxed);
        let _ = {
            let capture = self.capture.lock();
            input::handle_input(input, &capture)
        };
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
        session.fast_until.store(now_ms() + RTC_FAST_FRAME_MS, Ordering::Relaxed);
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
        if session.video_started.swap(true, Ordering::AcqRel) {
            return;
        }
        let manager = Arc::clone(self);
        tokio::spawn(async move {
            let capture_options = CaptureOptions::from_quality(&session.quality);
            let normal_delay = frame_delay(session.quality.fps_value());
            let mut next_frame = tokio::time::Instant::now();
            let mut last_report = tokio::time::Instant::now();
            let mut last_intra = tokio::time::Instant::now() - Duration::from_secs(5);
            let api = openh264::OpenH264API::from_source();
            let mut encoder = match Encoder::with_api_config(
                api,
                EncoderConfig::new()
                    .usage_type(UsageType::ScreenContentRealTime)
                    .bitrate(BitRate::from_bps(session.quality.bitrate_kbps_value().saturating_mul(1000)))
                    .max_frame_rate(FrameRate::from_hz(session.quality.fps_value() as f32))
                    .skip_frames(false)
                    .complexity(Complexity::Low)
                    .num_threads(4),
            ) {
                Ok(value) => value,
                Err(error) => {
                    log::warn(format!("rtc video encoder init failed session={}: {error}", session.session_id));
                    match Encoder::with_api_config(openh264::OpenH264API::from_source(), EncoderConfig::new()) {
                        Ok(value) => value,
                        Err(second) => {
                            log::warn(format!("rtc video encoder fallback failed session={}: {second}", session.session_id));
                            let _ = manager.out_tx.send(RtcOutbound::Json(ClientEvent::RtcState(RtcState {
                                session_id: session.session_id.clone(),
                                device_id: manager.device_id.clone(),
                                state: "encoder_failed".to_string(),
                                selected_candidate_type: "unknown".to_string(),
                                rtt_ms: 0,
                                bitrate_kbps: 0,
                                bytes_sent: 0,
                                bytes_received: 0,
                                packets_lost: 0,
                                error: format!("{error}; fallback={second}").chars().take(400).collect(),
                            })));
                            manager.close_session(&session.session_id).await;
                            return;
                        }
                    }
                }
            };
            log::info(format!(
                "rtc frame quality session={} profile={} maxSide={} fps={} jpeg={} bitrate={}kbps",
                session.session_id,
                session.quality.profile_name(),
                session.quality.max_side_value(),
                session.quality.fps_value(),
                session.quality.jpeg_quality_value(),
                session.quality.bitrate_kbps_value()
            ));
            loop {
                if matches!(session.pc.connection_state(), RTCPeerConnectionState::Failed | RTCPeerConnectionState::Closed) {
                    break;
                }
                let fast = now_ms() < session.fast_until.load(Ordering::Relaxed);
                let interval = if fast { Duration::from_millis(33) } else { normal_delay };
                let frame = {
                    let mut capture = manager.capture.lock();
                    capture.capture_frame_for_rtc(fast, capture_options)
                };
                match frame {
                    Ok(frame) => {
                        session.frames_captured.fetch_add(1, Ordering::Relaxed);
                        let source = BgraSliceU8::new(&frame.bgra, (frame.width as usize, frame.height as usize));
                        let yuv = YUVBuffer::from_bgra8_source(source);
                        if last_intra.elapsed() >= Duration::from_secs(2) {
                            last_intra = tokio::time::Instant::now();
                            encoder.force_intra_frame();
                        }
                        let encoded = match encoder.encode(&yuv) {
                            Ok(value) => Some(value.to_vec()),
                            Err(error) => {
                                log::warn(format!("rtc video encode failed session={}: {error}", session.session_id));
                                { *session.last_error.lock() = format!("encode: {error}"); }
                                None
                            }
                        };
                        if let Some(encoded) = encoded {
                            session.frames_encoded.fetch_add(1, Ordering::Relaxed);
                            if session.pc.connection_state() == RTCPeerConnectionState::Connected {
                                let sample = webrtc::media::Sample {
                                    data: Bytes::from(encoded),
                                    duration: Duration::from_millis((1000 / session.quality.fps_value().max(1)) as u64),
                                    ..Default::default()
                                };
                                match session.video_track.write_sample(&sample).await {
                                    Ok(()) => {
                                        session.bytes_written.fetch_add(sample.data.len() as u64, Ordering::Relaxed);
                                    }
                                    Err(error) => {
                                        session.write_errors.fetch_add(1, Ordering::Relaxed);
                                        { *session.last_error.lock() = format!("write: {error:#}"); }
                                        log::warn(format!("rtc video sample failed session={}: {error:#}", session.session_id));
                                    }
                                }
                            }
                        }
                    }
                    Err(error) => {
                        { *session.last_error.lock() = format!("capture: {error:#}"); }
                        log::warn(format!("rtc capture failed session={}: {error:#}", session.session_id));
                    }
                }
                if last_report.elapsed() >= Duration::from_secs(5) {
                    last_report = tokio::time::Instant::now();
                    let detail = format!(
                        "cap={},enc={},wr_bytes={},wr_err={},pcerr={},err={}",
                        session.frames_captured.load(Ordering::Relaxed),
                        session.frames_encoded.load(Ordering::Relaxed),
                        session.bytes_written.load(Ordering::Relaxed),
                        session.write_errors.load(Ordering::Relaxed),
                        session.pc.connection_state() as i32,
                        session.last_error.lock().chars().take(120).collect::<String>()
                    );
                    manager.send_state(&session.session_id, "media", "unknown", &detail);
                }
                next_frame += interval;
                let now = tokio::time::Instant::now();
                if next_frame > now {
                    tokio::time::sleep(next_frame - now).await;
                } else {
                    next_frame = now;
                }
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
                bytes_sent: 0,
                bytes_received: 0,
                packets_lost: 0,
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


fn merge_answer_video_sdp(sdp: &str) -> String {
    let lines: Vec<String> = sdp.lines().map(|line| line.to_string()).collect();
    let video_starts: Vec<usize> = lines
        .iter()
        .enumerate()
        .filter(|(_, line)| line.starts_with("m=video"))
        .map(|(index, _)| index)
        .collect();
    if video_starts.len() < 2 {
        return sdp.to_string();
    }
    let first = video_starts[0];
    let second = video_starts[1];
    let second_end = lines[second + 1..]
        .iter()
        .position(|line| line.starts_with("m="))
        .map(|offset| second + 1 + offset)
        .unwrap_or(lines.len());

    let first_section = &lines[first..second];
    let second_section = &lines[second..second_end];
    let second_mid = second_section
        .iter()
        .find_map(|line| line.strip_prefix("a=mid:"))
        .unwrap_or("1")
        .to_string();

    let mut moved: Vec<String> = second_section
        .iter()
        .filter(|line| line.starts_with("a=ssrc") || line.starts_with("a=msid"))
        .cloned()
        .collect();

    let mut merged_first: Vec<String> = Vec::new();
    let mut direction_seen = false;
    for line in first_section {
        if line.starts_with("a=sendrecv") || line.starts_with("a=recvonly") {
            merged_first.push("a=sendonly".to_string());
            direction_seen = true;
        } else {
            merged_first.push(line.clone());
        }
    }
    if !direction_seen {
        merged_first.push("a=sendonly".to_string());
    }
    merged_first.append(&mut moved);

    let mut head: Vec<String> = lines[..first].to_vec();
    for line in &mut head {
        if let Some(rest) = line.strip_prefix("a=group:BUNDLE") {
            let kept: Vec<&str> = rest
                .split_whitespace()
                .filter(|token| *token != second_mid)
                .collect();
            *line = format!("a=group:BUNDLE {}", kept.join(" "));
        }
    }

    let mut out = head;
    out.extend(merged_first);
    out.extend_from_slice(&lines[second_end..]);
    let mut text = out.join("\r\n");
    text.push_str("\r\n");
    text
}

fn frame_delay(fps: u32) -> Duration {
    Duration::from_millis((1000 / fps.max(1) as u64).clamp(16, 333))
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
