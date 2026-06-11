#!/usr/bin/env bash
set -euo pipefail

SERVER_URL="${BHZN_TODESK_SERVER:-}"
APP_DIR="${HOME}/Applications/BHZN-ToDesk-Agent"
APP_BUNDLE_NAME="BHZN ToDesk Agent.app"
APP_BUNDLE_TARGET="${HOME}/Applications/${APP_BUNDLE_NAME}"
PLIST_DIR="${HOME}/Library/LaunchAgents"
PLIST_PATH="${PLIST_DIR}/top.bhzn.todesk.agent.plist"
LOG_DIR="${HOME}/Library/Logs"
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_BUNDLE_SOURCE="${SOURCE_DIR}/${APP_BUNDLE_NAME}"

need_file() {
  if [ ! -f "$1" ]; then
    echo "Missing file: $1" >&2
    exit 1
  fi
}

need_file "${SOURCE_DIR}/uninstall-macos.sh"

mkdir -p "${APP_DIR}" "${PLIST_DIR}" "${LOG_DIR}"
cp "${SOURCE_DIR}/uninstall-macos.sh" "${APP_DIR}/uninstall-macos.sh"
chmod +x "${APP_DIR}/uninstall-macos.sh"

if [ -d "${APP_BUNDLE_SOURCE}" ]; then
  rm -rf "${APP_BUNDLE_TARGET}"
  cp -R "${APP_BUNDLE_SOURCE}" "${APP_BUNDLE_TARGET}"
  RUNNER_PATH="${APP_BUNDLE_TARGET}/Contents/MacOS/BHZN ToDesk Agent"
  RUNNER_WORKDIR="${APP_BUNDLE_TARGET}/Contents/MacOS"
  chmod +x "${RUNNER_PATH}"
else
  if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required."
    echo "Install Xcode Command Line Tools with: xcode-select --install"
    exit 1
  fi

  need_file "${SOURCE_DIR}/requirements.txt"
  need_file "${SOURCE_DIR}/run-macos.sh"

  cp "${SOURCE_DIR}/requirements.txt" "${APP_DIR}/requirements.txt"
  cp "${SOURCE_DIR}/run-macos.sh" "${APP_DIR}/run-macos.sh"

  if [ -f "${SOURCE_DIR}/agent.payload" ] && [ -f "${SOURCE_DIR}/run_payload_macos.py" ]; then
    cp "${SOURCE_DIR}/agent.payload" "${APP_DIR}/agent.payload"
    cp "${SOURCE_DIR}/run_payload_macos.py" "${APP_DIR}/run_payload_macos.py"
  else
    need_file "${SOURCE_DIR}/bhzn_desktop_agent.py"
    cp "${SOURCE_DIR}/bhzn_desktop_agent.py" "${APP_DIR}/bhzn_desktop_agent.py"
  fi

  chmod +x "${APP_DIR}/run-macos.sh"
  python3 -m venv "${APP_DIR}/.venv"
  "${APP_DIR}/.venv/bin/python" -m pip install --upgrade pip
  "${APP_DIR}/.venv/bin/python" -m pip install -r "${APP_DIR}/requirements.txt"
  touch "${APP_DIR}/.venv/.deps-installed"
  RUNNER_PATH="${APP_DIR}/run-macos.sh"
  RUNNER_WORKDIR="${APP_DIR}"
fi

echo ""
echo "Device identity:"
if [ -n "${SERVER_URL}" ]; then
  "${RUNNER_PATH}" --server "${SERVER_URL}" --show-id
else
  "${RUNNER_PATH}" --show-id
fi

cat > "${PLIST_PATH}" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>top.bhzn.todesk.agent</string>
  <key>ProgramArguments</key>
  <array>
    <string>${RUNNER_PATH}</string>
    <string>--nogui</string>
  </array>
  <key>WorkingDirectory</key>
  <string>${RUNNER_WORKDIR}</string>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>${LOG_DIR}/BHZN-ToDesk-Agent.out.log</string>
  <key>StandardErrorPath</key>
  <string>${LOG_DIR}/BHZN-ToDesk-Agent.err.log</string>
</dict>
</plist>
PLIST

launchctl bootout "gui/${UID}" "${PLIST_PATH}" >/dev/null 2>&1 || true
if ! launchctl bootstrap "gui/${UID}" "${PLIST_PATH}" >/dev/null 2>&1; then
  launchctl load -w "${PLIST_PATH}" >/dev/null 2>&1 || true
fi
launchctl kickstart -k "gui/${UID}/top.bhzn.todesk.agent" >/dev/null 2>&1 || true

echo ""
echo "BHZN ToDesk Agent has been installed."
echo "Support dir: ${APP_DIR}"
if [ -d "${APP_BUNDLE_TARGET}" ]; then
  echo "App bundle: ${APP_BUNDLE_TARGET}"
fi
echo "Config file: ${HOME}/Library/Application Support/BHZN-ToDesk/agent.json"
echo "Stdout log: ${LOG_DIR}/BHZN-ToDesk-Agent.out.log"
echo "Stderr log: ${LOG_DIR}/BHZN-ToDesk-Agent.err.log"
echo ""
echo "Required macOS permissions:"
if [ -d "${APP_BUNDLE_TARGET}" ]; then
  echo "1. Privacy & Security -> Screen Recording: allow BHZN ToDesk Agent"
  echo "2. Privacy & Security -> Accessibility: allow BHZN ToDesk Agent"
  echo "3. Privacy & Security -> Input Monitoring: allow BHZN ToDesk Agent"
else
  echo "1. Privacy & Security -> Screen Recording: allow Terminal and/or Python"
  echo "2. Privacy & Security -> Accessibility: allow Terminal and/or Python"
  echo "3. Privacy & Security -> Input Monitoring: allow Terminal and/or Python"
fi
echo ""
echo "After granting permissions, restart the agent:"
echo "launchctl kickstart -k gui/${UID}/top.bhzn.todesk.agent"
echo ""
echo "Show device ID and code:"
echo "${RUNNER_PATH} --show-id"

open "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture" >/dev/null 2>&1 || true
