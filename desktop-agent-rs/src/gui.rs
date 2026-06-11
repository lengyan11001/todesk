use std::sync::OnceLock;

use anyhow::{Context, Result};
use windows::core::w;
use windows::Win32::Foundation::{COLORREF, HWND, LPARAM, LRESULT, RECT, WPARAM};
use windows::Win32::Graphics::Gdi::{
    BeginPaint, CreateFontW, CreatePen, CreateSolidBrush, DeleteObject, DrawTextW, EndPaint,
    FillRect, InvalidateRect, RoundRect, SelectObject, SetBkMode, SetTextColor, UpdateWindow,
    CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_CHARSET, DEFAULT_PITCH, DT_CENTER, DT_LEFT,
    DT_NOPREFIX, DT_SINGLELINE, DT_TOP, DT_VCENTER, FF_DONTCARE, FW_BOLD, FW_NORMAL, HBRUSH, HDC,
    HFONT, HPEN, OUT_DEFAULT_PRECIS, PAINTSTRUCT, PS_SOLID, TRANSPARENT,
};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetClientRect, GetMessageW, LoadCursorW,
    PostQuitMessage, RegisterClassW, ShowWindow, TranslateMessage, CS_HREDRAW, CS_VREDRAW,
    CW_USEDEFAULT, IDC_ARROW, MSG, SW_SHOW, WINDOW_EX_STYLE, WM_DESTROY, WM_PAINT, WNDCLASSW,
    WS_CAPTION, WS_MINIMIZEBOX, WS_OVERLAPPED, WS_SYSMENU,
};

use crate::{config::AgentConfig, AGENT_VERSION};

static GUI_CONFIG: OnceLock<AgentConfig> = OnceLock::new();

const BLUE: u32 = 0x1677FF;
const BLUE_DARK: u32 = 0x0F5FD8;
const GREEN: u32 = 0x14994A;
const TEXT: u32 = 0x17202A;
const MUTED: u32 = 0x667586;
const LINE: u32 = 0xE3E9F1;
const BG: u32 = 0xF5F7FB;
const SIDEBAR: u32 = 0xEEF3F8;
const CARD: u32 = 0xFFFFFF;

pub fn run(config: AgentConfig) -> Result<()> {
    let _ = GUI_CONFIG.set(config);
    unsafe {
        let hinstance = GetModuleHandleW(None)?.into();
        let class_name = w!("BHZNToDeskAgentWindow");
        let wc = WNDCLASSW {
            lpfnWndProc: Some(window_proc),
            hInstance: hinstance,
            lpszClassName: class_name,
            hCursor: LoadCursorW(None, IDC_ARROW)?,
            style: CS_HREDRAW | CS_VREDRAW,
            ..Default::default()
        };
        let atom = RegisterClassW(&wc);
        if atom == 0 {
            return Err(windows::core::Error::from_thread()).context("RegisterClassW");
        }

        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            class_name,
            w!("BHZN ToDesk"),
            WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            760,
            548,
            None,
            None,
            Some(hinstance),
            None,
        )
        .context("CreateWindowExW")?;
        let _ = ShowWindow(hwnd, SW_SHOW);
        let _ = UpdateWindow(hwnd);
        let _ = InvalidateRect(Some(hwnd), None, true);

        let mut message = MSG::default();
        while GetMessageW(&mut message, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
    Ok(())
}

unsafe extern "system" fn window_proc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match msg {
        WM_PAINT => {
            paint(hwnd);
            LRESULT(0)
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            LRESULT(0)
        }
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

unsafe fn paint(hwnd: HWND) {
    let mut paint = PAINTSTRUCT::default();
    let hdc = BeginPaint(hwnd, &mut paint);
    let mut client = RECT::default();
    let _ = GetClientRect(hwnd, &mut client);

    fill_rect(hdc, &client, BG);
    draw_sidebar(hdc);
    draw_main(hdc);

    let _ = EndPaint(hwnd, &paint);
}

unsafe fn draw_sidebar(hdc: HDC) {
    fill_rect(hdc, &RECT { left: 0, top: 0, right: 154, bottom: 540 }, SIDEBAR);
    draw_text(hdc, "BHZN", 22, 28, 92, 54, BLUE, 18, true, DT_LEFT | DT_TOP | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, "Remote Desk", 22, 58, 132, 80, MUTED, 13, false, DT_LEFT | DT_TOP | DT_SINGLELINE | DT_NOPREFIX);

    draw_round_rect(hdc, 14, 112, 140, 148, 8, CARD, CARD);
    draw_round_rect(hdc, 24, 124, 34, 136, 4, BLUE, BLUE);
    draw_text(hdc, "主页", 44, 120, 112, 144, BLUE, 14, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);

    draw_text(hdc, "设备列表", 44, 172, 126, 194, TEXT, 14, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, "文件传输", 44, 218, 126, 240, TEXT, 14, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, "高级设置", 44, 264, 126, 286, TEXT, 14, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);

    draw_text(hdc, AGENT_VERSION, 22, 466, 132, 490, MUTED, 12, false, DT_LEFT | DT_TOP | DT_SINGLELINE | DT_NOPREFIX);
}

unsafe fn draw_main(hdc: HDC) {
    let config = GUI_CONFIG.get();
    let id = config.map(|item| item.device_id.as_str()).unwrap_or("-");
    let code = config.map(|item| item.verification_code.as_str()).unwrap_or("-");
    let server = config.map(|item| item.server.as_str()).unwrap_or("-");
    let name = config.map(|item| item.name.as_str()).unwrap_or("-");
    let log_path = config
        .map(|item| item.path.parent().map(|parent| parent.join("agent.log")).unwrap_or_else(|| "agent.log".into()))
        .map(|path| path.display().to_string())
        .unwrap_or_else(|| "-".to_string());

    draw_text(hdc, "通用场景", 184, 22, 270, 48, TEXT, 15, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    pill(hdc, 224, 64, 322, 100, "远程控制", true);
    pill(hdc, 336, 64, 442, 100, "投屏演示", false);
    pill(hdc, 456, 64, 562, 100, "互传文件", false);
    pill(hdc, 576, 64, 682, 100, "快速设置", false);

    draw_text(hdc, "允许控制本设备", 184, 126, 330, 152, TEXT, 16, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_round_rect(hdc, 334, 130, 366, 148, 9, BLUE, BLUE);
    draw_text(hdc, "已连接到服务器", 386, 126, 540, 152, GREEN, 13, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);

    draw_round_rect(hdc, 184, 168, 704, 272, 12, CARD, CARD);
    draw_text(hdc, "设备代码", 210, 190, 310, 214, MUTED, 13, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, id, 210, 220, 386, 258, 0x000000, 22, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, "连接模式-临时密码", 456, 190, 640, 214, MUTED, 13, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, code, 456, 220, 626, 258, 0x000000, 22, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    fill_rect(hdc, &RECT { left: 430, top: 184, right: 431, bottom: 256 }, LINE);

    draw_text(hdc, "本机信息", 184, 304, 270, 330, TEXT, 15, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_round_rect(hdc, 184, 344, 704, 428, 10, CARD, CARD);
    draw_text(hdc, &format!("设备名称  {name}"), 210, 362, 650, 386, TEXT, 13, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, &format!("服务器  {server}"), 210, 390, 650, 414, TEXT, 13, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);

    draw_round_rect(hdc, 184, 448, 440, 486, 8, 0xEEF7FF, 0xD8EAFF);
    draw_text(hdc, &format!("日志  {log_path}"), 204, 456, 424, 480, MUTED, 12, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_round_rect(hdc, 460, 448, 704, 486, 8, 0xFFF7E5, 0xFFE1A6);
    draw_text(hdc, "文件传输功能待开放", 480, 456, 684, 480, 0x8A5A00, 13, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
}

unsafe fn pill(hdc: HDC, left: i32, top: i32, right: i32, bottom: i32, text: &str, active: bool) {
    let fill = if active { BLUE } else { 0xF1F6FF };
    let border = if active { BLUE } else { 0xE7EEF8 };
    let color = if active { 0xFFFFFF } else { BLUE_DARK };
    draw_round_rect(hdc, left, top, right, bottom, 16, fill, border);
    draw_text(hdc, text, left, top, right, bottom, color, 13, true, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
}

unsafe fn fill_rect(hdc: HDC, rect: &RECT, color: u32) {
    let brush = CreateSolidBrush(colorref(color));
    FillRect(hdc, rect, brush);
    let _ = DeleteObject(brush.into());
}

unsafe fn draw_round_rect(hdc: HDC, left: i32, top: i32, right: i32, bottom: i32, radius: i32, fill: u32, border: u32) {
    let brush: HBRUSH = CreateSolidBrush(colorref(fill));
    let pen: HPEN = CreatePen(PS_SOLID, 1, colorref(border));
    let old_brush = SelectObject(hdc, brush.into());
    let old_pen = SelectObject(hdc, pen.into());
    let _ = RoundRect(hdc, left, top, right, bottom, radius, radius);
    let _ = SelectObject(hdc, old_pen);
    let _ = SelectObject(hdc, old_brush);
    let _ = DeleteObject(pen.into());
    let _ = DeleteObject(brush.into());
}

unsafe fn draw_text(
    hdc: HDC,
    text: &str,
    left: i32,
    top: i32,
    right: i32,
    bottom: i32,
    color: u32,
    size: i32,
    bold: bool,
    format: windows::Win32::Graphics::Gdi::DRAW_TEXT_FORMAT,
) {
    let font = create_font(size, bold);
    let old_font = SelectObject(hdc, font.into());
    let mut wide: Vec<u16> = text.encode_utf16().collect();
    let mut rect = RECT { left, top, right, bottom };
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, colorref(color));
    let _ = DrawTextW(hdc, &mut wide, &mut rect, format);
    let _ = SelectObject(hdc, old_font);
    let _ = DeleteObject(font.into());
}

unsafe fn create_font(size: i32, bold: bool) -> HFONT {
    CreateFontW(
        -size,
        0,
        0,
        0,
        if bold { FW_BOLD.0 as i32 } else { FW_NORMAL.0 as i32 },
        0,
        0,
        0,
        DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS,
        CLIP_DEFAULT_PRECIS,
        CLEARTYPE_QUALITY,
        DEFAULT_PITCH.0 as u32 | FF_DONTCARE.0 as u32,
        w!("Microsoft YaHei UI"),
    )
}

fn colorref(rgb: u32) -> COLORREF {
    let r = (rgb >> 16) & 0xff;
    let g = (rgb >> 8) & 0xff;
    let b = rgb & 0xff;
    COLORREF((b << 16) | (g << 8) | r)
}
