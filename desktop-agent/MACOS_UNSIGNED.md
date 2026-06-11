# BHZN ToDesk Agent for macOS

This is an unsigned internal test package. It is meant for devices where the user explicitly installs the remote-control client and grants macOS permissions.

## Install

Double-click `Install.command`.

If this package was built on macOS with `build-macos-app-package.sh`, it contains `BHZN ToDesk Agent.app` and installs that app bundle to `~/Applications`. If it was built on Windows with `build-macos-unsigned-package.py`, it installs the Python runner and encrypted payload instead. Both modes use the same device ID/config path and the same H5 control flow.

If macOS blocks it because the package is unsigned, use Terminal:

```bash
cd /path/to/BHZN-ToDesk-Agent-mac
xattr -dr com.apple.quarantine .
bash install-macos.sh
```

The installer will:

- Copy the agent to `~/Applications/BHZN-ToDesk-Agent`
- Create a Python virtual environment
- Install dependencies
- Generate or reuse a stable device ID and verification code
- Register a user LaunchAgent for login/startup persistence
- Start the background agent

## Required Permissions

Open System Settings -> Privacy & Security and allow Terminal and/or Python in:

- Screen Recording
- Accessibility
- Input Monitoring

After granting permissions, restart the agent:

```bash
launchctl kickstart -k gui/$(id -u)/top.bhzn.todesk.agent
```

## Show Device ID And Code

```bash
~/Applications/BHZN-ToDesk-Agent/run-macos.sh --show-id
```

## Logs

```bash
tail -f ~/Library/Logs/BHZN-ToDesk-Agent.out.log
tail -f ~/Library/Logs/BHZN-ToDesk-Agent.err.log
```

## Uninstall

```bash
bash ~/Applications/BHZN-ToDesk-Agent/uninstall-macos.sh
```

## Signing Status

This package is not signed with Apple Developer ID and is not notarized. It is suitable for internal testing only. A trusted public release still needs Apple Developer ID signing, notarization, and a hardened runtime build.
