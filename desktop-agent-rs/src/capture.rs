use std::io::Cursor;

use anyhow::{Context, Result};
use image::{codecs::jpeg::JpegEncoder, DynamicImage, ImageBuffer, Rgba};

use crate::protocol::ScreenInfo;

const MAX_SIDE: u32 = 1280;
const FAST_MAX_SIDE: u32 = 960;

#[derive(Debug)]
pub struct CaptureState {
    screen: ScreenInfo,
}

#[derive(Debug)]
pub struct CaptureFrame {
    pub frame_id: u64,
    pub frame_kind: String,
    pub image: Vec<u8>,
    pub width: u32,
    pub height: u32,
    pub input_width: u32,
    pub input_height: u32,
    pub timestamp: u64,
}

impl CaptureState {
    pub fn new() -> Self {
        Self {
            screen: ScreenInfo { width: 0, height: 0, input_width: 0, input_height: 0 },
        }
    }

    pub fn screen_info(&self) -> ScreenInfo {
        self.screen
    }

    pub fn capture_frame(&mut self, fast: bool) -> Result<CaptureFrame> {
        let image = capture_primary_monitor().context("capture primary monitor")?;
        let input_width = image.width();
        let input_height = image.height();
        let max_side = if fast { FAST_MAX_SIDE } else { MAX_SIDE };
        let image = resize_rgba(image, max_side);
        let width = image.width();
        let height = image.height();
        let quality = if fast { 32 } else { 48 };
        let encoded = encode_jpeg(image, quality)?;
        self.screen = ScreenInfo { width, height, input_width, input_height };
        let timestamp = now_ms();
        Ok(CaptureFrame {
            frame_id: timestamp,
            frame_kind: "jpeg".to_string(),
            image: encoded,
            width,
            height,
            input_width,
            input_height,
            timestamp,
        })
    }
}

#[cfg(windows)]
fn capture_primary_monitor() -> Result<ImageBuffer<Rgba<u8>, Vec<u8>>> {
    use windows::Win32::Graphics::Gdi::{
        BitBlt, CreateCompatibleBitmap, CreateCompatibleDC, DeleteDC, DeleteObject, GetDC, GetDIBits,
        ReleaseDC, SelectObject, BITMAPINFO, BITMAPINFOHEADER, BI_RGB, DIB_RGB_COLORS, HBITMAP,
        HGDIOBJ, SRCCOPY,
    };
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN};

    unsafe {
        let width = GetSystemMetrics(SM_CXSCREEN);
        let height = GetSystemMetrics(SM_CYSCREEN);
        if width <= 0 || height <= 0 {
            anyhow::bail!("invalid screen size");
        }

        let hwnd = HWND(std::ptr::null_mut());
        let screen_dc = GetDC(Some(hwnd));
        if screen_dc.0.is_null() {
            anyhow::bail!("GetDC failed");
        }
        let memory_dc = CreateCompatibleDC(Some(screen_dc));
        if memory_dc.0.is_null() {
            ReleaseDC(Some(hwnd), screen_dc);
            anyhow::bail!("CreateCompatibleDC failed");
        }
        let bitmap = CreateCompatibleBitmap(screen_dc, width, height);
        if bitmap.0.is_null() {
            let _ = DeleteDC(memory_dc);
            ReleaseDC(Some(hwnd), screen_dc);
            anyhow::bail!("CreateCompatibleBitmap failed");
        }
        let old = SelectObject(memory_dc, HGDIOBJ(bitmap.0));
        BitBlt(memory_dc, 0, 0, width, height, Some(screen_dc), 0, 0, SRCCOPY)
            .ok()
            .context("BitBlt failed")?;

        let mut info = BITMAPINFO {
            bmiHeader: BITMAPINFOHEADER {
                biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
                biWidth: width,
                biHeight: -height,
                biPlanes: 1,
                biBitCount: 32,
                biCompression: BI_RGB.0,
                ..Default::default()
            },
            ..Default::default()
        };
        let mut bgra = vec![0u8; width as usize * height as usize * 4];
        let lines = GetDIBits(
            memory_dc,
            HBITMAP(bitmap.0),
            0,
            height as u32,
            Some(bgra.as_mut_ptr().cast()),
            &mut info,
            DIB_RGB_COLORS,
        );

        SelectObject(memory_dc, old);
        let _ = DeleteObject(HGDIOBJ(bitmap.0));
        let _ = DeleteDC(memory_dc);
        ReleaseDC(Some(hwnd), screen_dc);

        if lines == 0 {
            anyhow::bail!("GetDIBits failed");
        }

        for px in bgra.chunks_exact_mut(4) {
            px.swap(0, 2);
            px[3] = 255;
        }
        ImageBuffer::from_raw(width as u32, height as u32, bgra).context("construct image buffer")
    }
}

#[cfg(not(windows))]
fn capture_primary_monitor() -> Result<ImageBuffer<Rgba<u8>, Vec<u8>>> {
    anyhow::bail!("capture is only implemented on Windows in this clean-room agent version")
}

fn resize_rgba(image: ImageBuffer<Rgba<u8>, Vec<u8>>, max_side: u32) -> DynamicImage {
    let image = DynamicImage::ImageRgba8(image);
    let width = image.width();
    let height = image.height();
    let side = width.max(height);
    if side <= max_side {
        return image;
    }
    let scale = max_side as f32 / side as f32;
    let new_width = ((width as f32 * scale).round() as u32).max(1);
    let new_height = ((height as f32 * scale).round() as u32).max(1);
    image.resize(new_width, new_height, image::imageops::FilterType::Triangle)
}

fn encode_jpeg(image: DynamicImage, quality: u8) -> Result<Vec<u8>> {
    let rgb = image.to_rgb8();
    let mut out = Cursor::new(Vec::new());
    let mut encoder = JpegEncoder::new_with_quality(&mut out, quality);
    encoder.encode_image(&DynamicImage::ImageRgb8(rgb))?;
    Ok(out.into_inner())
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
