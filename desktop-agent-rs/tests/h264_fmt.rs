use openh264::encoder::{BitRate, Complexity, Encoder, EncoderConfig, FrameRate, UsageType};
use openh264::formats::{BgraSliceU8, YUVBuffer};

#[test]
fn dump_h264_prefix() {
    let api = openh264::OpenH264API::from_source();
    let mut enc = Encoder::with_api_config(
        api,
        EncoderConfig::new()
            .usage_type(UsageType::ScreenContentRealTime)
            .bitrate(BitRate::from_bps(1_800_000))
            .max_frame_rate(FrameRate::from_hz(30.0))
            .skip_frames(true)
            .complexity(Complexity::Low)
            .num_threads(4),
    ).unwrap();
    let (w, h) = (640usize, 360usize);
    let mut bgra = vec![0u8; w * h * 4];
    for y in 0..h { for x in 0..w { let i = (y * w + x) * 4; bgra[i] = (x % 256) as u8; bgra[i+1] = (y % 256) as u8; bgra[i+2] = 128; bgra[i+3] = 255; } }
    let src = BgraSliceU8::new(&bgra, (w, h));
    let yuv = YUVBuffer::from_bgra8_source(src);
    let out = enc.encode(&yuv).unwrap();
    let bytes = out.to_vec();
    println!("len={} first16={:02x?}", bytes.len(), &bytes[..16.min(bytes.len())]);
    let has_start = bytes.windows(4).any(|s| s == [0,0,0,1]);
    let has_short = bytes.windows(3).any(|s| s == [0,0,1]);
    println!("annexb4={has_start} annexb3={has_short}");
}
