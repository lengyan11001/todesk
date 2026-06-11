# Clean-Room Rules

Goal: build a low-latency remote desktop agent using the same architectural category as mature remote desktop products, without copying AGPL project code.

## What We Can Use

- Our existing BHZN protocol and product code.
- MIT/Apache/BSD crates from crates.io.
- OS APIs: Windows input APIs, screen capture APIs, macOS accessibility/screen recording APIs.
- General design concepts:
  - dedicated screen capture loop
  - input loop independent from screen loop
  - dropping stale frames
  - adaptive FPS/quality
  - separate cursor/input state
  - future video transport instead of JPEG screenshot transport

## What We Must Not Use

- RustDesk main application code licensed under AGPL-3.0.
- Direct copies or close translations of AGPL files.
- Internal RustDesk protocol implementation as our product protocol.

## Dependency Policy

Every dependency added to `Cargo.toml` must have a permissive license or a license approved by the project owner. Record important non-standard dependencies here.

Current notable dependencies:

- `tokio-tungstenite`: MIT, WebSocket client.
- `image`: MIT, JPEG encoding for compatibility mode.
- `windows`: MIT/Apache-2.0, Windows API bindings.

## Migration Plan

1. Rust agent compatible with existing H5 relay, still using JPEG frames.
2. Add low-latency frame control: input-triggered capture, stale-frame dropping, frame ACK.
3. Add video frame transport for H5 playback.
4. Retire Python desktop agent after Windows/macOS Rust packages are stable.
