use std::sync::Arc;
use webrtc::api::interceptor_registry::register_default_interceptors;
use webrtc::api::media_engine::{MediaEngine, MIME_TYPE_H264};
use webrtc::api::APIBuilder;
use webrtc::interceptor::registry::Registry;
use webrtc::peer_connection::configuration::RTCConfiguration;
use webrtc::peer_connection::sdp::session_description::RTCSessionDescription;
use webrtc::rtp_transceiver::rtp_codec::RTCRtpCodecCapability;
use webrtc::track::track_local::track_local_static_sample::TrackLocalStaticSample;
use webrtc::track::track_local::TrackLocal;

async fn new_api() -> anyhow::Result<webrtc::api::API> {
    let mut m = MediaEngine::default();
    m.register_default_codecs()?;
    let mut registry = Registry::new();
    registry = register_default_interceptors(registry, &mut m)?;
    Ok(APIBuilder::new().with_media_engine(m).with_interceptor_registry(registry).build())
}

fn summarize(tag: &str, sdp: &str) {
    println!("--- {tag} ---");
    for line in sdp.lines() {
        let t = line.trim();
        if t.starts_with("m=") || t.starts_with("a=mid:") || t.starts_with("a=sendonly")
            || t.starts_with("a=sendrecv") || t.starts_with("a=recvonly")
            || t.starts_with("a=ssrc") || t.starts_with("a=msid") || t.starts_with("a=group:BUNDLE") {
            println!("{t}");
        }
    }
}

async fn run_case(name: &str, track_first: bool) -> anyhow::Result<()> {
    let api = new_api().await?;
    let offerer = api.new_peer_connection(RTCConfiguration::default()).await?;
    let answerer = api.new_peer_connection(RTCConfiguration::default()).await?;
    offerer.create_data_channel("control", None).await?;
    offerer.add_transceiver_from_kind(webrtc::rtp_transceiver::rtp_codec::RTPCodecType::Video, None).await?;
    let offer = offerer.create_offer(None).await?;
    offerer.set_local_description(offer.clone()).await?;

    let track = Arc::new(TrackLocalStaticSample::new(
        RTCRtpCodecCapability {
            mime_type: MIME_TYPE_H264.to_owned(),
            clock_rate: 90_000,
            sdp_fmtp_line: "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42001f".to_string(),
            ..Default::default()
        },
        "screen".to_string(), "bhzn".to_string(),
    ));
    if track_first {
        answerer.add_track(Arc::clone(&track) as Arc<dyn TrackLocal + Send + Sync>).await?;
        answerer.set_remote_description(offer).await?;
    } else {
        answerer.set_remote_description(offer).await?;
        answerer.add_track(Arc::clone(&track) as Arc<dyn TrackLocal + Send + Sync>).await?;
    }
    for tr in answerer.get_transceivers().await {
        if tr.kind() == webrtc::rtp_transceiver::rtp_codec::RTPCodecType::Video {
            tr.set_direction(webrtc::rtp_transceiver::rtp_transceiver_direction::RTCRtpTransceiverDirection::Sendonly).await;
        }
    }
    let answer = answerer.create_answer(None).await?;
    let applied = answerer.set_local_description(answer.clone()).await;
    println!("set_local_description({name}) = {:?}", applied.map(|_| "ok"));
    summarize(name, &answer.sdp);
    offerer.close().await?; answerer.close().await?;
    Ok(())
}

#[tokio::test]
async fn probe_answer_ssrc() -> anyhow::Result<()> {
    run_case("add_track_before_SRD", true).await?;
    run_case("add_track_after_SRD", false).await?;
    Ok(())
}
