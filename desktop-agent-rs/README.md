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
E:\BHZN-ToDesk\desktop-agent-rs\target\release\bhzn-todesk-agent-rs.exe
```

Run with the default shared desktop-agent config:

```powershell
.\target\release\bhzn-todesk-agent-rs.exe
```

Run with a specific config file:

```powershell
.\target\release\bhzn-todesk-agent-rs.exe --config E:\BHZN-ToDesk\desktop-agent-rs\test-agent.json
```

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
