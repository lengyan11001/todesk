# BHZN ToDesk Rust Agent

This is the clean-room desktop agent line for BHZN ToDesk.

It is intentionally not a fork of RustDesk and must not copy AGPL-licensed RustDesk application code. The first version keeps protocol compatibility with the existing BHZN relay/H5 so it can replace the Python desktop agent incrementally.

## Scope

- Windows first.
- macOS later with the same protocol.
- Existing BHZN login, CMS, device binding, verification code and H5 controller stay unchanged.
- Screen transport will be upgraded from JPEG frames to browser-decodable video frames after the Rust agent is stable.

## Build

Install Rust and MSVC Build Tools first.

```powershell
cd E:\BHZN-ToDesk\desktop-agent-rs
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1
```

Output:

```text
E:\BHZN-ToDesk\desktop-agent-rs\dist\BHZN-ToDesk-Agent.exe
E:\BHZN-ToDesk\desktop-agent-rs\dist\BHZN-ToDesk-Agent-Setup.exe
```

## Install / Startup / Update

No administrator privilege or trusted certificate is required in the current internal build.

Install for the current user:

```powershell
.\dist\BHZN-ToDesk-Agent-Setup.exe
```

Or explicitly:

```powershell
.\dist\BHZN-ToDesk-Agent.exe --install
```

The installer copies the client to `%LOCALAPPDATA%\BHZN-ToDesk\BHZN-ToDesk-Agent.exe`, registers `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, and creates a user scheduled task named `BHZN-ToDesk-Agent-Update`.

Manual update check:

```powershell
.\dist\BHZN-ToDesk-Agent.exe --check-update --headless --no-auto-install
```

Uninstall startup/update entries:

```powershell
.\dist\BHZN-ToDesk-Agent.exe --uninstall
```

Run with the default config:

```powershell
.\dist\BHZN-ToDesk-Agent.exe --no-update
```

Run with a specific config file:

```powershell
.\dist\BHZN-ToDesk-Agent.exe --config E:\BHZN-ToDesk\desktop-agent-rs\test-agent.json --no-update
```

Local config is stored under `%APPDATA%\BHZN-ToDesk\agent.json`. On Windows, sensitive values are saved with DPAPI protected fields.

## License Boundary

Allowed:

- Original BHZN code.
- MIT/Apache/BSD licensed crates.
- Public OS APIs and documentation.
- Clean-room implementation of ideas such as video streaming, QoS, frame dropping, and independent input/cursor channels.

Not allowed:

- Copying RustDesk main project source files such as `src/server/video_service.rs`, `src/server/connection.rs`, or other AGPL business/protocol code.
- Porting AGPL code by line-by-line translation.

See [docs/CLEAN_ROOM.md](docs/CLEAN_ROOM.md).
