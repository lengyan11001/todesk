use std::{
    path::Path,
    sync::{
        atomic::{AtomicUsize, Ordering},
        OnceLock,
    },
};

use anyhow::{Context, Result};
use windows::core::{w, PCWSTR};
use windows::Win32::Foundation::{COLORREF, HWND, LPARAM, LRESULT, RECT, WPARAM};
use windows::Win32::Graphics::Gdi::{
    BeginPaint, CreateFontW, CreatePen, CreateSolidBrush, DeleteObject, DrawTextW, EndPaint,
    FillRect, InvalidateRect, RoundRect, SelectObject, SetBkMode, SetTextColor, UpdateWindow,
    CLIP_DEFAULT_PRECIS,
    CLEARTYPE_QUALITY, DEFAULT_CHARSET, DEFAULT_PITCH, DT_CENTER, DT_LEFT, DT_NOPREFIX,
    DT_SINGLELINE, DT_TOP, DT_VCENTER, FF_DONTCARE, FW_BOLD, FW_NORMAL, HBRUSH, HDC, HFONT,
    HPEN, OUT_DEFAULT_PRECIS, PAINTSTRUCT, PS_SOLID, TRANSPARENT,
};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::Shell::ShellExecuteW;
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetClientRect, GetMessageW, LoadCursorW,
    PostQuitMessage, RegisterClassW, ShowWindow, TranslateMessage, CS_HREDRAW, CS_VREDRAW,
    CW_USEDEFAULT, IDC_ARROW, MSG, SW_SHOW, WINDOW_EX_STYLE, WM_DESTROY, WM_LBUTTONDOWN, WM_PAINT,
    WNDCLASSW, WS_CAPTION, WS_MINIMIZEBOX, WS_OVERLAPPED, WS_SYSMENU,
};

use crate::{config::AgentConfig, file_transfer, AGENT_VERSION};

static GUI_CONFIG: OnceLock<AgentConfig> = OnceLock::new();
static ACTIVE_TAB: AtomicUsize = AtomicUsize::new(0);

const BLUE: u32 = 0x1677FF;
const BLUE_DARK: u32 = 0x0F5FD8;
const GREEN: u32 = 0x14994A;
const TEXT: u32 = 0x17202A;
const MUTED: u32 = 0x667586;
const LINE: u32 = 0xE3E9F1;
const BG: u32 = 0xF5F7FB;
const SIDEBAR: u32 = 0xEEF3F8;
const CARD: u32 = 0xFFFFFF;
const WARNING_BG: u32 = 0xFFF7E5;
const WARNING_LINE: u32 = 0xFFE1A6;

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
            780,
            568,
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
        WM_LBUTTONDOWN => {
            let x = (lparam.0 as i16) as i32;
            let y = ((lparam.0 >> 16) as i16) as i32;
            handle_click(hwnd, x, y);
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
    if ACTIVE_TAB.load(Ordering::Relaxed) == 1 {
        draw_files(hdc);
    } else {
        draw_main(hdc);
    }

    let _ = EndPaint(hwnd, &paint);
}

unsafe fn draw_sidebar(hdc: HDC) {
    let active = ACTIVE_TAB.load(Ordering::Relaxed);
    fill_rect(hdc, &RECT { left: 0, top: 0, right: 154, bottom: 560 }, SIDEBAR);
    draw_text(hdc, "BHZN", 22, 28, 92, 54, BLUE, 18, true, DT_LEFT | DT_TOP | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, "Remote Desk", 22, 58, 132, 80, MUTED, 13, false, DT_LEFT | DT_TOP | DT_SINGLELINE | DT_NOPREFIX);

    sidebar_item(hdc, 112, "主页", active == 0);
    sidebar_item(hdc, 158, "设备列表", false);
    sidebar_item(hdc, 204, "文件传输", active == 1);
    sidebar_item(hdc, 250, "高级设置", false);

    draw_text(hdc, AGENT_VERSION, 22, 486, 132, 510, MUTED, 12, false, DT_LEFT | DT_TOP | DT_SINGLELINE | DT_NOPREFIX);
}

unsafe fn sidebar_item(hdc: HDC, top: i32, text: &str, active: bool) {
    if active {
        draw_round_rect(hdc, 14, top, 140, top + 36, 8, CARD, CARD);
    }
    draw_round_rect(hdc, 24, top + 12, 34, top + 24, 4, if active { BLUE } else { MUTED }, if active { BLUE } else { MUTED });
    draw_text(
        hdc,
        text,
        44,
        top + 8,
        126,
        top + 32,
        if active { BLUE } else { TEXT },
        14,
        active,
        DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX,
    );
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

    draw_round_rect(hdc, 184, 168, 724, 272, 12, CARD, CARD);
    draw_text(hdc, "设备代码", 210, 190, 310, 214, MUTED, 13, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, id, 210, 220, 386, 258, 0x000000, 22, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, "连接模式-临时密码", 456, 190, 640, 214, MUTED, 13, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, code, 456, 220, 626, 258, 0x000000, 22, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    fill_rect(hdc, &RECT { left: 430, top: 184, right: 431, bottom: 256 }, LINE);

    draw_text(hdc, "本机信息", 184, 304, 270, 330, TEXT, 15, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_round_rect(hdc, 184, 344, 724, 428, 10, CARD, CARD);
    draw_text(hdc, &format!("设备名称  {name}"), 210, 362, 690, 386, TEXT, 13, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, &format!("服务器  {server}"), 210, 390, 690, 414, TEXT, 13, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);

    draw_round_rect(hdc, 184, 448, 452, 486, 8, 0xEEF7FF, 0xD8EAFF);
    draw_text(hdc, &format!("日志  {log_path}"), 204, 456, 436, 480, MUTED, 12, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_round_rect(hdc, 472, 448, 724, 486, 8, WARNING_BG, WARNING_LINE);
    draw_text(hdc, "文件会保存到 Downloads\\BHZN-ToDesk", 492, 456, 708, 480, 0x8A5A00, 13, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
}

unsafe fn draw_files(hdc: HDC) {
    draw_text(hdc, "文件传输", 184, 24, 310, 52, TEXT, 18, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_round_rect(hdc, 586, 24, 724, 58, 8, 0xEEF7FF, 0xD8EAFF);
    draw_text(hdc, "打开接收目录", 604, 31, 710, 52, BLUE_DARK, 13, true, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);

    draw_round_rect(hdc, 184, 86, 724, 128, 8, CARD, CARD);
    draw_text(hdc, "文件名", 204, 96, 384, 120, MUTED, 13, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, "大小", 398, 96, 458, 120, MUTED, 13, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, "接收时间", 474, 96, 560, 120, MUTED, 13, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    draw_text(hdc, "操作", 626, 96, 690, 120, MUTED, 13, false, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);

    let records = file_transfer::recent_records(6);
    if records.is_empty() {
        draw_round_rect(hdc, 184, 148, 724, 248, 10, CARD, CARD);
        draw_text(hdc, "暂无接收文件", 204, 178, 704, 204, TEXT, 16, true, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
        draw_text(hdc, "H5 下发文件后，会显示在这里。", 204, 210, 704, 232, MUTED, 13, false, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
        return;
    }

    for (index, record) in records.iter().enumerate() {
        let top = 146 + index as i32 * 64;
        draw_round_rect(hdc, 184, top, 724, top + 54, 8, CARD, LINE);
        draw_text(hdc, &record.file_name, 204, top + 8, 384, top + 28, TEXT, 13, true, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
        draw_text(hdc, &record.path, 204, top + 30, 596, top + 48, MUTED, 11, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
        draw_text(hdc, &file_transfer::format_bytes(record.bytes), 398, top + 14, 458, top + 38, TEXT, 12, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
        draw_text(hdc, &file_transfer::received_label(record.received_at_ms), 474, top + 14, 560, top + 38, TEXT, 12, false, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
        draw_round_rect(hdc, 610, top + 12, 694, top + 42, 7, BLUE, BLUE);
        draw_text(hdc, "打开", 610, top + 15, 694, top + 39, 0xFFFFFF, 13, true, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    }
}

unsafe fn handle_click(hwnd: HWND, x: i32, y: i32) {
    if x < 154 {
        if (112..=148).contains(&y) {
            ACTIVE_TAB.store(0, Ordering::Relaxed);
            refresh(hwnd);
            return;
        }
        if (204..=240).contains(&y) {
            ACTIVE_TAB.store(1, Ordering::Relaxed);
            refresh(hwnd);
            return;
        }
    }

    if ACTIVE_TAB.load(Ordering::Relaxed) == 0 && (456..=562).contains(&x) && (64..=100).contains(&y) {
        ACTIVE_TAB.store(1, Ordering::Relaxed);
        refresh(hwnd);
        return;
    }

    if ACTIVE_TAB.load(Ordering::Relaxed) != 1 {
        return;
    }
    if (586..=724).contains(&x) && (24..=58).contains(&y) {
        if let Ok(dir) = file_transfer::receive_dir() {
            open_path(hwnd, &dir.display().to_string());
        }
        return;
    }

    let records = file_transfer::recent_records(6);
    for (index, record) in records.iter().enumerate() {
        let top = 146 + index as i32 * 64;
        if (610..=694).contains(&x) && (top + 12..=top + 42).contains(&y) {
            open_path(hwnd, &record.path);
            return;
        }
    }
}

unsafe fn refresh(hwnd: HWND) {
    let _ = InvalidateRect(Some(hwnd), None, true);
    let _ = UpdateWindow(hwnd);
}

unsafe fn open_path(hwnd: HWND, path: &str) {
    let fallback_parent = Path::new(path).parent().map(|parent| parent.display().to_string());
    let target = if Path::new(path).exists() {
        path.to_string()
    } else {
        fallback_parent.unwrap_or_else(|| path.to_string())
    };
    let wide = wide_null(&target);
    let _ = ShellExecuteW(Some(hwnd), w!("open"), PCWSTR(wide.as_ptr()), PCWSTR::null(), PCWSTR::null(), SW_SHOW);
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

fn wide_null(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(std::iter::once(0)).collect()
}
