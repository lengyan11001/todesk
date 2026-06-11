use anyhow::{Context, Result};

use crate::{capture::CaptureState, protocol::InputEvent};

pub fn handle_input(event: InputEvent, capture: &CaptureState) -> Result<()> {
    let screen = capture.screen_info();
    let x = scale(event.x.unwrap_or(0.0), screen.width, screen.input_width);
    let y = scale(event.y.unwrap_or(0.0), screen.height, screen.input_height);
    let x2 = scale(event.x2.unwrap_or(0.0), screen.width, screen.input_width);
    let y2 = scale(event.y2.unwrap_or(0.0), screen.height, screen.input_height);
    let button = event.button.as_deref().unwrap_or("left");
    match event.action.as_str() {
        "tap" => click(x, y, button),
        "rightClick" => click(x, y, "right"),
        "dragStart" => {
            move_mouse(x, y)?;
            mouse_down(button)
        }
        "dragMove" => move_mouse(x, y),
        "dragEnd" => {
            move_mouse(x, y)?;
            mouse_up(button)
        }
        "swipe" | "homeSwipe" => drag(x, y, x2, y2, event.duration.unwrap_or(120.0), button),
        "scroll" => scroll(event.delta_y.unwrap_or(0.0)),
        "key" => key(event.key.as_deref().unwrap_or(""), event.modifiers.unwrap_or_default()),
        "text" => text(event.text.as_deref().unwrap_or("")),
        "back" => hotkey(&["alt", "left"]),
        "home" => key("meta", Vec::new()),
        _ => Ok(()),
    }
}

fn scale(value: f64, frame: u32, input: u32) -> i32 {
    if frame == 0 || input == 0 {
        return value.round().max(0.0) as i32;
    }
    (value * input as f64 / frame as f64).round().max(0.0) as i32
}

#[cfg(windows)]
fn move_mouse(x: i32, y: i32) -> Result<()> {
    use windows::Win32::UI::WindowsAndMessaging::SetCursorPos;
    unsafe { SetCursorPos(x, y).ok().context("SetCursorPos") }
}

#[cfg(windows)]
fn mouse_down(button: &str) -> Result<()> {
    mouse_event(button, true)
}

#[cfg(windows)]
fn mouse_up(button: &str) -> Result<()> {
    mouse_event(button, false)
}

#[cfg(windows)]
fn mouse_event(button: &str, down: bool) -> Result<()> {
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        mouse_event, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MIDDLEDOWN,
        MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP,
    };
    let flag = match (button, down) {
        ("right", true) => MOUSEEVENTF_RIGHTDOWN,
        ("right", false) => MOUSEEVENTF_RIGHTUP,
        ("middle", true) => MOUSEEVENTF_MIDDLEDOWN,
        ("middle", false) => MOUSEEVENTF_MIDDLEUP,
        (_, true) => MOUSEEVENTF_LEFTDOWN,
        (_, false) => MOUSEEVENTF_LEFTUP,
    };
    unsafe { mouse_event(flag, 0, 0, 0, 0) };
    Ok(())
}

fn click(x: i32, y: i32, button: &str) -> Result<()> {
    move_mouse(x, y)?;
    mouse_down(button)?;
    mouse_up(button)
}

fn drag(x: i32, y: i32, x2: i32, y2: i32, duration_ms: f64, button: &str) -> Result<()> {
    move_mouse(x, y)?;
    mouse_down(button)?;
    let steps = ((duration_ms / 8.0).round() as i32).clamp(3, 40);
    for step in 1..=steps {
        let nx = x + (x2 - x) * step / steps;
        let ny = y + (y2 - y) * step / steps;
        move_mouse(nx, ny)?;
        std::thread::sleep(std::time::Duration::from_millis(4));
    }
    mouse_up(button)
}

fn scroll(delta_y: f64) -> Result<()> {
    #[cfg(windows)]
    unsafe {
        use windows::Win32::UI::Input::KeyboardAndMouse::{mouse_event, MOUSEEVENTF_WHEEL};
        let amount = (-(delta_y as i32).clamp(-10, 10)) * 120;
        mouse_event(MOUSEEVENTF_WHEEL, 0, 0, amount, 0);
    }
    Ok(())
}

fn key(key: &str, modifiers: Vec<String>) -> Result<()> {
    if !modifiers.is_empty() {
        let parts: Vec<&str> = modifiers.iter().map(String::as_str).chain(std::iter::once(key)).collect();
        return hotkey(&parts);
    }
    key_click(vk_code(key))
}

fn hotkey(keys: &[&str]) -> Result<()> {
    let parsed: Vec<u16> = keys.iter().map(|key| vk_code(key)).collect();
    for key in &parsed {
        key_event(*key, true)?;
    }
    for key in parsed.iter().rev() {
        key_event(*key, false)?;
    }
    Ok(())
}

fn text(value: &str) -> Result<()> {
    if value.is_empty() {
        return Ok(());
    }
    for unit in value.encode_utf16() {
        unicode_unit(unit)?;
    }
    Ok(())
}

fn vk_code(key: &str) -> u16 {
    match key.to_ascii_lowercase().as_str() {
        "enter" | "return" => 0x0D,
        "backspace" => 0x08,
        "delete" => 0x2E,
        "escape" | "esc" => 0x1B,
        "tab" => 0x09,
        "space" | " " => 0x20,
        "arrowleft" | "left" => 0x25,
        "arrowup" | "up" => 0x26,
        "arrowright" | "right" => 0x27,
        "arrowdown" | "down" => 0x28,
        "ctrl" | "control" => 0x11,
        "alt" => 0x12,
        "shift" => 0x10,
        "meta" | "win" | "command" | "cmd" => 0x5B,
        other => {
            let ch = other.chars().next().unwrap_or(' ');
            ch.to_ascii_uppercase() as u16
        }
    }
}

fn key_click(vk: u16) -> Result<()> {
    key_event(vk, true)?;
    key_event(vk, false)
}

#[cfg(windows)]
fn key_event(vk: u16, down: bool) -> Result<()> {
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYBD_EVENT_FLAGS, KEYEVENTF_KEYUP,
        VIRTUAL_KEY,
    };
    let flags = if down { KEYBD_EVENT_FLAGS(0) } else { KEYEVENTF_KEYUP };
    let input = INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: VIRTUAL_KEY(vk),
                wScan: 0,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    };
    let sent = unsafe { SendInput(&[input], std::mem::size_of::<INPUT>() as i32) };
    if sent == 0 {
        anyhow::bail!("SendInput key failed");
    }
    Ok(())
}

#[cfg(windows)]
fn unicode_unit(unit: u16) -> Result<()> {
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYBD_EVENT_FLAGS,
        KEYEVENTF_KEYUP, KEYEVENTF_UNICODE, VIRTUAL_KEY,
    };
    for flags in [KEYEVENTF_UNICODE, KEYBD_EVENT_FLAGS(KEYEVENTF_UNICODE.0 | KEYEVENTF_KEYUP.0)] {
        let input = INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: VIRTUAL_KEY(0),
                    wScan: unit,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        };
        let sent = unsafe { SendInput(&[input], std::mem::size_of::<INPUT>() as i32) };
        if sent == 0 {
            anyhow::bail!("SendInput unicode failed");
        }
    }
    Ok(())
}
