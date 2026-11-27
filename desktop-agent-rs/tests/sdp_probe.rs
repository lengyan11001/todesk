use std::sync::Arc;

use anyhow::Result;
use webrtc::api::media_engine::{MediaEngine, MIME_TYPE_H264};
use webrtc::api::APIBuilder;
use webrtc::peer_connection::configuration::RTCConfiguration;
use webrtc::rtp_transceiver::rtp_codec::{RTPCodecType, RTCRtpCodecCapability};
use webrtc::track::track_local::track_local_static_sample::TrackLocalStaticSample;
use webrtc::track::track_local::TrackLocal;

#[tokio::test]
async fn answer_attaches_to_offered_video_mid() -> Result<()> {
    let mut media = MediaEngine::default();
    media.register_default_codecs()?;
    let api = APIBuilder::new().with_media_engine(media).build();

    let offerer = api.new_peer_connection(RTCConfiguration::default()).await?;
    let answerer = api.new_peer_connection(RTCConfiguration::default()).await?;

    offerer.create_data_channel("control", None).await?;
    offerer
        .add_transceiver_from_kind(RTPCodecType::Video, None)
        .await?;
    let offer = offerer.create_offer(None).await?;
    offerer.set_local_description(offer.clone()).await?;

    let track = Arc::new(TrackLocalStaticSample::new(
        RTCRtpCodecCapability {
            mime_type: MIME_TYPE_H264.to_owned(),
            clock_rate: 90_000,
            sdp_fmtp_line: "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42001f".to_string(),
            ..Default::default()
        },
        "screen".to_string(),
        "bhzn".to_string(),
    ));
    answerer
        .add_track(Arc::clone(&track) as Arc<dyn TrackLocal + Send + Sync>)
        .await?;
    answerer.set_remote_description(offer).await?;
    let answer = answerer.create_answer(None).await?;
    answerer.set_local_description(answer.clone()).await?;

    let sdp = answer.sdp;
    let video_count = sdp.matches("m=video").count();
    println!("video_count={video_count}");
    for line in sdp.lines() {
        if line.starts_with("m=")
            || line.starts_with("a=mid:")
            || line.starts_with("a=sendonly")
            || line.starts_with("a=sendrecv")
            || line.starts_with("a=recvonly")
        {
            println!("{line}");
        }
    }
    offerer.close().await?;
    answerer.close().await?;
    println!("all_m_lines={}", sdp.matches("m=").count());
    Ok(())
}
