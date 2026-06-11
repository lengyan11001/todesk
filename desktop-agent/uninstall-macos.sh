#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${HOME}/Applications/BHZN-ToDesk-Agent"
APP_BUNDLE="${HOME}/Applications/BHZN ToDesk Agent.app"
PLIST_PATH="${HOME}/Library/LaunchAgents/top.bhzn.todesk.agent.plist"

launchctl bootout "gui/${UID}" "${PLIST_PATH}" >/dev/null 2>&1 || true
launchctl unload -w "${PLIST_PATH}" >/dev/null 2>&1 || true
rm -f "${PLIST_PATH}"
rm -rf "${APP_DIR}"
rm -rf "${APP_BUNDLE}"

echo "BHZN ToDesk Agent has been removed."
