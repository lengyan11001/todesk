import argparse
import base64
import ctypes
import json
import os
import platform
import queue
import random
import socket
import string
import sys
import threading
import time
from urllib.parse import urlparse
try:
    import tkinter as tk
    from tkinter import messagebox
except Exception:
    tk = None
    messagebox = None
from io import BytesIO
from pathlib import Path

import mss
import pyautogui
import pyperclip
import websocket
from PIL import Image


APP_NAME = "BHZN ToDesk Desktop Agent"
AGENT_VERSION = "0.1.4"
FRAME_INTERVAL = 0.075
DRAG_FRAME_INTERVAL = 0.03
HEARTBEAT_INTERVAL = 15.0
JPEG_QUALITY = 45
DRAG_JPEG_QUALITY = 34
MAX_SIDE = 1280
DRAG_MAX_SIDE = 1280
RECONNECT_MIN = 1.5
RECONNECT_MAX = 30.0
DRAG_FAST_MODE_SECONDS = 0.8


def reveal_text(values, key: int) -> str:
    return "".join(chr(value ^ key) for value in values)


DEFAULT_SERVER = reveal_text(
    [50, 46, 46, 42, 41, 96, 117, 117, 46, 53, 62, 63, 41, 49, 116, 56, 50, 32, 52, 116, 46, 53, 42],
    0x5A,
)

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0
if platform.system().lower() == "windows":
    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass


def config_dir() -> Path:
    system = platform.system().lower()
    if system == "windows":
        root = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
        return Path(root) / "BHZN-ToDesk"
    if system == "darwin":
        return Path.home() / "Library" / "Application Support" / "BHZN-ToDesk"
    return Path.home() / ".config" / "bhzn-todesk"


def normalize_server_url(value: str) -> str:
    url = (value or DEFAULT_SERVER).strip().rstrip("/")
    if not url:
        url = DEFAULT_SERVER
    if not url.startswith(("http://", "https://", "ws://", "wss://")):
        url = "https://" + url
    if url == DEFAULT_SERVER.replace("https://", "http://"):
        url = DEFAULT_SERVER
    if url == DEFAULT_SERVER.replace("https://", "ws://"):
        url = DEFAULT_SERVER.replace("https://", "wss://")
    return url


def ws_url(server_url: str) -> str:
    url = normalize_server_url(server_url)
    if url.startswith("https://"):
        url = "wss://" + url[len("https://") :]
    elif url.startswith("http://"):
        url = "ws://" + url[len("http://") :]
    if url.startswith("ws://") and not is_local_url(url):
        raise ValueError("Cleartext websocket is disabled for non-local servers. Use https:// or wss://.")
    if not url.endswith("/ws"):
        url += "/ws"
    return url


def is_local_url(value: str) -> bool:
    try:
        host = (urlparse(value).hostname or "").lower()
    except Exception:
        return False
    return host in {"localhost", "127.0.0.1", "::1"} or host.startswith("127.")


def normalize_device_id(value: str) -> str:
    clean = "".join(ch for ch in value.upper() if ch in string.ascii_uppercase + string.digits)
    if len(clean) < 8:
        clean = (clean + random_token(8))[:8]
    return f"{clean[:4]}-{clean[4:8]}"


def random_token(length: int) -> str:
    alphabet = string.ascii_uppercase + string.digits
    return "".join(random.choice(alphabet) for _ in range(length))


def random_code() -> str:
    return "".join(random.choice(string.digits) for _ in range(6))


def platform_name() -> str:
    system = platform.system().lower()
    if system == "windows":
        return "windows"
    if system == "darwin":
        return "macos"
    if system == "linux":
        return "linux"
    return system or "desktop"


class AgentConfig:
    def __init__(self, path: Path, data: dict):
        self.path = path
        self.server = normalize_server_url(data.get("server") or DEFAULT_SERVER)
        self.device_id = normalize_device_id(data.get("deviceId") or random_token(8))
        self.verification_code = str(data.get("verificationCode") or random_code())[:8]
        self.name = data.get("name") or default_device_name()

    @classmethod
    def load(cls, custom_path: str = ""):
        path = Path(custom_path).expanduser() if custom_path else config_dir() / "agent.json"
        data = {}
        if path.exists():
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except Exception:
                backup = path.with_suffix(f".{int(time.time())}.bad")
                try:
                    path.replace(backup)
                except Exception:
                    pass
        config = cls(path, data)
        config.save()
        return config

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "server": self.server,
            "deviceId": self.device_id,
            "verificationCode": self.verification_code,
            "name": self.name,
        }
        self.path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def default_device_name() -> str:
    host = socket.gethostname() or "Desktop"
    if platform_name() == "windows":
        return f"BHZN Windows {host}"
    if platform_name() == "macos":
        return f"BHZN Mac {host}"
    return f"BHZN Desktop {host}"


class DesktopAgent:
    def __init__(self, config: AgentConfig):
        self.config = config
        self.ws = None
        self.running = True
        self.connected = False
        self.capture_enabled = True
        self.control_enabled = True
        self.screen = {"width": 0, "height": 0, "inputWidth": 0, "inputHeight": 0}
        self.input_origin = {"x": 0, "y": 0}
        self.input_queue: queue.Queue[dict] = queue.Queue(maxsize=512)
        self.capture_thread = None
        self.heartbeat_thread = None
        self.dragging = False
        self.drag_button = "left"
        self.fast_frame_until = 0.0
        self.pointer_pos = None
        self.input_thread = threading.Thread(target=self.input_loop, daemon=True)
        self.input_thread.start()

    def run(self):
        delay = RECONNECT_MIN
        while self.running:
            try:
                self.connect_once()
                delay = RECONNECT_MIN
            except KeyboardInterrupt:
                self.running = False
                break
            except Exception as exc:
                self.connected = False
                print(f"[agent] connection ended: {exc}", flush=True)
            if not self.running:
                break
            time.sleep(delay)
            delay = min(RECONNECT_MAX, delay * 2)

    def stop(self):
        self.running = False
        self.close()

    def connect_once(self):
        url = ws_url(self.config.server)
        print(f"[agent] connecting {url}", flush=True)
        self.ws = websocket.WebSocketApp(
            url,
            on_open=self.on_open,
            on_message=self.on_message,
            on_error=self.on_error,
            on_close=self.on_close,
        )
        self.ws.run_forever(ping_interval=20, ping_timeout=10)

    def on_open(self, _ws):
        self.connected = True
        print("[agent] connected", flush=True)
        self.send_status("hello-device")
        self.capture_thread = threading.Thread(target=self.capture_loop, daemon=True)
        self.capture_thread.start()
        self.heartbeat_thread = threading.Thread(target=self.heartbeat_loop, daemon=True)
        self.heartbeat_thread.start()

    def on_close(self, _ws, status_code, message):
        self.connected = False
        print(f"[agent] closed {status_code or ''} {message or ''}", flush=True)

    def on_error(self, _ws, error):
        self.connected = False
        print(f"[agent] websocket error: {error}", flush=True)

    def on_message(self, _ws, raw: str):
        try:
            msg = json.loads(raw)
        except Exception:
            return
        msg_type = msg.get("type")
        if msg_type == "hello":
            print(f"[agent] device online id={self.config.device_id}", flush=True)
            return
        if msg_type == "control-request":
            self.send_status("status")
            return
        if msg_type == "stop-control":
            self.send_status("status")
            return
        if msg_type == "server-replaced":
            print("[agent] another client replaced this device session", flush=True)
            self.close()
            return
        if msg_type == "input":
            try:
                self.input_queue.put_nowait(msg)
            except queue.Full:
                pass

    def close(self):
        self.connected = False
        self.release_drag()
        if self.ws:
            self.ws.close()

    def send_json(self, payload: dict) -> bool:
        ws = self.ws
        if not ws or not self.connected:
            return False
        try:
            ws.send(json.dumps(payload, separators=(",", ":")))
            return True
        except Exception as exc:
            self.connected = False
            print(f"[agent] send failed: {exc}", flush=True)
            try:
                ws.close()
            except Exception:
                pass
            return False

    def base_status(self, msg_type: str) -> dict:
        return {
            "type": msg_type,
            "id": self.config.device_id,
            "verificationCode": self.config.verification_code,
            "name": self.config.name,
            "model": platform.machine() or platform.processor() or "",
            "platform": platform_name(),
            "osVersion": platform.platform(),
            "agentVersion": AGENT_VERSION,
            "permissions": {
                "screenCapture": self.capture_enabled,
                "inputControl": self.control_enabled,
            },
            "controlEnabled": bool(self.control_enabled and self.capture_enabled),
            "screen": self.screen,
        }

    def send_status(self, msg_type: str = "status"):
        self.send_json(self.base_status(msg_type))

    def heartbeat_loop(self):
        while self.running and self.connected:
            time.sleep(HEARTBEAT_INTERVAL)
            self.send_status("heartbeat")

    def capture_loop(self):
        last_error_at = 0.0
        with mss.mss() as sct:
            while self.running and self.connected:
                start = time.time()
                fast_frame = start < self.fast_frame_until
                try:
                    monitor = sct.monitors[1] if len(sct.monitors) > 1 else sct.monitors[0]
                    shot = sct.grab(monitor)
                    self.screen = {
                        "width": 0,
                        "height": 0,
                        "inputWidth": int(monitor["width"]),
                        "inputHeight": int(monitor["height"]),
                    }
                    self.input_origin = {
                        "x": int(monitor.get("left", 0)),
                        "y": int(monitor.get("top", 0)),
                    }
                    image = Image.frombytes("RGB", shot.size, shot.rgb)
                    image = self.resize_for_wire(image, DRAG_MAX_SIDE if fast_frame else MAX_SIDE)
                    self.screen["width"], self.screen["height"] = image.size
                    encoded = self.encode_jpeg(image, DRAG_JPEG_QUALITY if fast_frame else JPEG_QUALITY)
                    self.capture_enabled = True
                    self.send_json(
                        {
                            "type": "frame",
                            "image": encoded,
                            "width": image.size[0],
                            "height": image.size[1],
                            "timestamp": int(time.time() * 1000),
                        }
                    )
                except Exception as exc:
                    self.capture_enabled = False
                    now = time.time()
                    if now - last_error_at > 5:
                        print(f"[agent] capture failed: {exc}", flush=True)
                        self.send_status("status")
                        last_error_at = now
                elapsed = time.time() - start
                target_interval = DRAG_FRAME_INTERVAL if fast_frame else FRAME_INTERVAL
                time.sleep(max(0.001, target_interval - elapsed))

    def resize_for_wire(self, image: Image.Image, max_side_limit: int = MAX_SIDE) -> Image.Image:
        width, height = image.size
        max_side = max(width, height)
        if max_side <= max_side_limit:
            return image
        scale = max_side_limit / float(max_side)
        new_size = (max(1, int(width * scale)), max(1, int(height * scale)))
        return image.resize(new_size, Image.Resampling.BILINEAR)

    def encode_jpeg(self, image: Image.Image, quality: int = JPEG_QUALITY) -> str:
        out = BytesIO()
        image.save(out, format="JPEG", quality=quality, optimize=False)
        return base64.b64encode(out.getvalue()).decode("ascii")

    def input_loop(self):
        while self.running:
            msg = self.input_queue.get()
            try:
                self.handle_input(msg)
                self.control_enabled = True
            except Exception as exc:
                self.control_enabled = False
                print(f"[agent] input failed: {exc}", flush=True)
                self.send_status("status")

    def scale_x(self, value) -> int:
        return self.scale_axis(value, self.screen.get("width"), self.screen.get("inputWidth"))

    def scale_y(self, value) -> int:
        return self.scale_axis(value, self.screen.get("height"), self.screen.get("inputHeight"))

    @staticmethod
    def scale_axis(value, frame_size, input_size) -> int:
        try:
            value = float(value)
            frame_size = float(frame_size or 0)
            input_size = float(input_size or 0)
        except Exception:
            return 0
        if frame_size <= 0 or input_size <= 0:
            return max(0, int(value))
        return max(0, int(round(value * input_size / frame_size)))

    def handle_input(self, msg: dict):
        action = str(msg.get("action") or "")
        duration = max(0.01, min(5.0, float(msg.get("duration") or 80) / 1000.0))
        x = self.scale_x(msg.get("x", 0))
        y = self.scale_y(msg.get("y", 0))
        x2 = self.scale_x(msg.get("x2", 0))
        y2 = self.scale_y(msg.get("y2", 0))
        button = self.normalize_button(msg.get("button") or "left")

        if action == "tap":
            self.click(x, y, button=button)
        elif action == "doubleTap":
            pyautogui.click(x=x, y=y, clicks=2, interval=0.05, button=button)
        elif action == "rightClick":
            self.click(x, y, button="right")
        elif action in ("swipe", "homeSwipe"):
            self.move_pointer(x, y)
            pyautogui.dragTo(x2, y2, duration=duration, button=button)
        elif action == "dragStart":
            self.enter_fast_frame_mode()
            self.release_drag()
            self.move_pointer(x, y)
            self.mouse_down(button)
            self.dragging = True
            self.drag_button = button
        elif action == "dragMove":
            self.enter_fast_frame_mode()
            if not self.dragging:
                self.mouse_down(button)
                self.dragging = True
                self.drag_button = button
            self.move_pointer(x, y, smooth=True)
        elif action == "dragEnd":
            self.enter_fast_frame_mode()
            if self.dragging:
                self.move_pointer(x, y, smooth=True)
                self.mouse_up(self.drag_button)
            else:
                self.move_pointer(x, y)
                self.mouse_up(button)
            self.dragging = False
            self.drag_button = "left"
        elif action == "scroll":
            delta_y = int(msg.get("deltaY") or 0)
            pyautogui.scroll(-max(-10, min(10, delta_y)))
        elif action == "back":
            self.hotkey("alt", "left") if platform_name() == "windows" else self.hotkey("command", "[")
        elif action == "home":
            self.press_home()
        elif action == "key":
            key = str(msg.get("key") or "")
            modifiers = [str(item).lower() for item in msg.get("modifiers") or []]
            if key:
                self.hotkey(*modifiers, self.normalize_key(key)) if modifiers else pyautogui.press(self.normalize_key(key))
        elif action == "text":
            text = str(msg.get("text") or "")
            if text:
                self.input_text(text)

    @staticmethod
    def normalize_button(value: str) -> str:
        value = str(value or "left").lower()
        if value in ("left", "right", "middle"):
            return value
        return "left"

    @staticmethod
    def normalize_key(value: str) -> str:
        mapping = {
            " ": "space",
            "esc": "escape",
            "escape": "escape",
            "enter": "enter",
            "return": "enter",
            "backspace": "backspace",
            "delete": "delete",
            "arrowup": "up",
            "arrowdown": "down",
            "arrowleft": "left",
            "arrowright": "right",
            "meta": "win",
            "command": "command",
            "cmd": "command",
            "control": "ctrl",
            "ctrl": "ctrl",
            "alt": "alt",
            "shift": "shift",
            "tab": "tab",
        }
        key = str(value or "").lower()
        return mapping.get(key, key)

    def hotkey(self, *keys: str):
        keys = [self.normalize_key(key) for key in keys if key]
        if keys:
            pyautogui.hotkey(*keys)

    def enter_fast_frame_mode(self):
        self.fast_frame_until = max(self.fast_frame_until, time.time() + DRAG_FAST_MODE_SECONDS)

    def absolute_point(self, x: int, y: int) -> tuple[int, int]:
        return int(self.input_origin.get("x", 0)) + int(x), int(self.input_origin.get("y", 0)) + int(y)

    def move_pointer(self, x: int, y: int, smooth: bool = False):
        ax, ay = self.absolute_point(x, y)
        if platform_name() == "windows":
            if smooth and self.pointer_pos:
                px, py = self.pointer_pos
                distance = max(abs(ax - px), abs(ay - py))
                steps = max(1, min(6, int(distance / 18)))
                for step in range(1, steps + 1):
                    nx = int(round(px + (ax - px) * step / steps))
                    ny = int(round(py + (ay - py) * step / steps))
                    ctypes.windll.user32.SetCursorPos(nx, ny)
                    if steps > 1:
                        time.sleep(0.001)
            else:
                ctypes.windll.user32.SetCursorPos(int(ax), int(ay))
        else:
            pyautogui.moveTo(ax, ay, duration=0)
        self.pointer_pos = (int(ax), int(ay))

    def mouse_down(self, button: str = "left"):
        if platform_name() == "windows":
            ctypes.windll.user32.mouse_event(self.mouse_event_flag(button, "down"), 0, 0, 0, 0)
        else:
            pyautogui.mouseDown(button=button)

    def mouse_up(self, button: str = "left"):
        if platform_name() == "windows":
            ctypes.windll.user32.mouse_event(self.mouse_event_flag(button, "up"), 0, 0, 0, 0)
        else:
            pyautogui.mouseUp(button=button)

    def click(self, x: int, y: int, button: str = "left"):
        self.move_pointer(x, y)
        self.mouse_down(button)
        self.mouse_up(button)

    @staticmethod
    def mouse_event_flag(button: str, phase: str) -> int:
        flags = {
            ("left", "down"): 0x0002,
            ("left", "up"): 0x0004,
            ("right", "down"): 0x0008,
            ("right", "up"): 0x0010,
            ("middle", "down"): 0x0020,
            ("middle", "up"): 0x0040,
        }
        return flags.get((button, phase), flags[("left", phase)])

    def release_drag(self):
        if self.dragging:
            try:
                self.mouse_up(self.drag_button)
            except Exception:
                pass
        self.dragging = False
        self.drag_button = "left"

    def input_text(self, text: str):
        if not text:
            return
        try:
            old_clipboard = pyperclip.paste()
        except Exception:
            old_clipboard = None
        try:
            pyperclip.copy(text)
            if platform_name() == "macos":
                self.hotkey("command", "v")
            else:
                self.hotkey("ctrl", "v")
            time.sleep(0.02)
        except Exception:
            pyautogui.write(text, interval=0)
        finally:
            if old_clipboard is not None:
                try:
                    pyperclip.copy(old_clipboard)
                except Exception:
                    pass

    def press_home(self):
        if platform_name() == "windows":
            pyautogui.press("win")
        elif platform_name() == "macos":
            self.hotkey("command", "space")
        else:
            pyautogui.press("home")


def parse_args(argv):
    parser = argparse.ArgumentParser(description=APP_NAME)
    parser.add_argument("--server", default="", help="Server URL, default production HTTPS endpoint")
    parser.add_argument("--config", default="", help="Custom config file path")
    parser.add_argument("--show-id", action="store_true", help="Print saved device id/code and exit")
    parser.add_argument("--nogui", action="store_true", help="Run without the desktop status window")
    return parser.parse_args(argv)


class AgentGui:
    def __init__(self, config: AgentConfig):
        self.config = config
        self.agent = None
        self.agent_thread = None
        self.root = tk.Tk()
        self.root.title(APP_NAME)
        self.root.geometry("430x360")
        self.root.resizable(False, False)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

        self.status_var = tk.StringVar(value="未启动")
        self.server_var = tk.StringVar(value=config.server)
        self.id_var = tk.StringVar(value=config.device_id)
        self.code_var = tk.StringVar(value=config.verification_code)

        self.build()
        self.start_agent()
        self.refresh_status()

    def build(self):
        root = self.root
        root.configure(bg="#f4f7fb")
        frame = tk.Frame(root, bg="#ffffff", padx=18, pady=16)
        frame.pack(fill="both", expand=True, padx=18, pady=18)

        tk.Label(frame, text="BHZN ToDesk Agent", bg="#ffffff", fg="#17202a", font=("Segoe UI", 16, "bold")).pack(anchor="w")
        tk.Label(frame, text="Windows/macOS 被控端", bg="#ffffff", fg="#667586").pack(anchor="w", pady=(2, 14))

        self.row(frame, "服务器", self.server_var.get())
        self.row(frame, "设备 ID", self.id_var.get(), copy_value=self.id_var.get())
        self.row(frame, "验证码", self.code_var.get(), copy_value=self.code_var.get())

        status_line = tk.Frame(frame, bg="#ffffff")
        status_line.pack(fill="x", pady=(12, 4))
        tk.Label(status_line, text="状态", bg="#ffffff", fg="#667586", width=10, anchor="w").pack(side="left")
        tk.Label(status_line, textvariable=self.status_var, bg="#ffffff", fg="#1677ff", font=("Segoe UI", 10, "bold")).pack(side="left")

        button_line = tk.Frame(frame, bg="#ffffff")
        button_line.pack(fill="x", pady=(18, 8))
        tk.Button(button_line, text="复制设备 ID", command=lambda: self.copy(self.id_var.get()), width=14).pack(side="left", padx=(0, 8))
        tk.Button(button_line, text="复制验证码", command=lambda: self.copy(self.code_var.get()), width=14).pack(side="left", padx=(0, 8))
        tk.Button(button_line, text="重启连接", command=self.restart_agent, width=12).pack(side="left")

        tk.Label(
            frame,
            text="把设备 ID 和验证码填到 H5 控制台即可绑定。macOS 需要给本程序或 Python 授权 Screen Recording、Accessibility、Input Monitoring。",
            bg="#ffffff",
            fg="#667586",
            wraplength=360,
            justify="left",
        ).pack(anchor="w", pady=(12, 0))

    def row(self, parent, label, value, copy_value=None):
        line = tk.Frame(parent, bg="#ffffff")
        line.pack(fill="x", pady=5)
        tk.Label(line, text=label, bg="#ffffff", fg="#667586", width=10, anchor="w").pack(side="left")
        tk.Label(line, text=value, bg="#eef3f8", fg="#17202a", anchor="w", padx=8, pady=5).pack(side="left", fill="x", expand=True)
        if copy_value:
            tk.Button(line, text="复制", command=lambda: self.copy(copy_value), width=6).pack(side="left", padx=(8, 0))

    def copy(self, value):
        self.root.clipboard_clear()
        self.root.clipboard_append(value)
        self.root.update_idletasks()

    def start_agent(self):
        if self.agent and self.agent.running:
            return
        self.agent = DesktopAgent(self.config)
        self.agent_thread = threading.Thread(target=self.agent.run, daemon=True)
        self.agent_thread.start()

    def restart_agent(self):
        if self.agent:
            self.agent.stop()
        time.sleep(0.2)
        self.start_agent()

    def refresh_status(self):
        if self.agent and self.agent.connected:
            self.status_var.set("在线，正在等待 H5 控制")
        elif self.agent and self.agent.running:
            self.status_var.set("连接中")
        else:
            self.status_var.set("已停止")
        self.root.after(1000, self.refresh_status)

    def on_close(self):
        if messagebox.askokcancel("退出", "退出后这台电脑会离线，确认退出？"):
            if self.agent:
                self.agent.stop()
            self.root.destroy()

    def run(self):
        self.root.mainloop()


def main(argv=None):
    args = parse_args(argv or sys.argv[1:])
    config = AgentConfig.load(args.config)
    if args.server:
        config.server = normalize_server_url(args.server)
        config.save()
    print(f"{APP_NAME} {AGENT_VERSION}", flush=True)
    print(f"设备 ID: {config.device_id}", flush=True)
    print(f"验证码: {config.verification_code}", flush=True)
    print(f"服务器: {config.server}", flush=True)
    print(f"配置文件: {config.path}", flush=True)
    if args.show_id:
        return 0
    if not args.nogui and tk is not None:
        gui = AgentGui(config)
        gui.run()
        return 0
    if not args.nogui:
        print("GUI is not available on this Python runtime, running in background mode.", flush=True)
    agent = DesktopAgent(config)
    agent.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
