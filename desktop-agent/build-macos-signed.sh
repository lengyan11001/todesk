#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${APP_NAME:-BHZN ToDesk Agent}"
BUNDLE_ID="${BUNDLE_ID:-top.bhzn.todesk.agent}"
PKG_ID="${PKG_ID:-top.bhzn.todesk.agent.pkg}"
VERSION="${VERSION:-0.1.20}"
SERVER_URL="${SERVER_URL:-https://todesk.bhzn.top}"
APP_CERT="${DEVELOPER_ID_APPLICATION:-}"
INSTALLER_CERT="${DEVELOPER_ID_INSTALLER:-}"
TEAM_ID="${APPLE_TEAM_ID:-}"
APPLE_ID_VALUE="${APPLE_ID:-}"
APPLE_PASSWORD="${APPLE_APP_PASSWORD:-}"
ASC_KEY_ID_VALUE="${ASC_KEY_ID:-}"
ASC_ISSUER_ID_VALUE="${ASC_ISSUER_ID:-}"
ASC_KEY_PATH_VALUE="${ASC_KEY_PATH:-}"

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="${ROOT_DIR}/dist-macos"
BUILD_DIR="${ROOT_DIR}/build-macos"
APP_PATH="${DIST_DIR}/${APP_NAME}.app"
PKG_ROOT="${BUILD_DIR}/pkgroot"
LAUNCH_AGENT_PATH="${PKG_ROOT}/Library/LaunchAgents/${BUNDLE_ID}.plist"
PKG_PATH="${DIST_DIR}/${APP_NAME}-${VERSION}.pkg"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This script must run on macOS."
  exit 1
fi

if [[ -z "${APP_CERT}" ]]; then
  echo "Missing DEVELOPER_ID_APPLICATION, for example:"
  echo 'export DEVELOPER_ID_APPLICATION="Developer ID Application: Company Name (TEAMID)"'
  exit 1
fi

if [[ -z "${INSTALLER_CERT}" ]]; then
  echo "Missing DEVELOPER_ID_INSTALLER, for example:"
  echo 'export DEVELOPER_ID_INSTALLER="Developer ID Installer: Company Name (TEAMID)"'
  exit 1
fi

rm -rf "${DIST_DIR}" "${BUILD_DIR}"
mkdir -p "${DIST_DIR}" "${BUILD_DIR}"

python3 -m venv "${ROOT_DIR}/.venv"
"${ROOT_DIR}/.venv/bin/python" -m pip install --upgrade pip
"${ROOT_DIR}/.venv/bin/python" -m pip install -r "${ROOT_DIR}/requirements.txt" pyinstaller

"${ROOT_DIR}/.venv/bin/pyinstaller" \
  --clean \
  --windowed \
  --collect-all aiortc \
  --collect-all aioice \
  --collect-all av \
  --name "${APP_NAME}" \
  --osx-bundle-identifier "${BUNDLE_ID}" \
  "${ROOT_DIR}/bhzn_desktop_agent.py"

mv "${ROOT_DIR}/dist/${APP_NAME}.app" "${APP_PATH}"

/usr/bin/codesign \
  --force \
  --deep \
  --timestamp \
  --options runtime \
  --entitlements "${ROOT_DIR}/macos-entitlements.plist" \
  --sign "${APP_CERT}" \
  "${APP_PATH}"

/usr/bin/codesign --verify --deep --strict --verbose=2 "${APP_PATH}"
/usr/sbin/spctl -a -vv "${APP_PATH}" || true

mkdir -p "${PKG_ROOT}/Applications" "${PKG_ROOT}/Library/LaunchAgents"
cp -R "${APP_PATH}" "${PKG_ROOT}/Applications/${APP_NAME}.app"

cat > "${LAUNCH_AGENT_PATH}" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${BUNDLE_ID}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/Applications/${APP_NAME}.app/Contents/MacOS/${APP_NAME}</string>
    <string>--server</string>
    <string>${SERVER_URL}</string>
    <string>--nogui</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>/tmp/bhzn-todesk-agent.out.log</string>
  <key>StandardErrorPath</key>
  <string>/tmp/bhzn-todesk-agent.err.log</string>
</dict>
</plist>
PLIST

/usr/bin/pkgbuild \
  --root "${PKG_ROOT}" \
  --identifier "${PKG_ID}" \
  --version "${VERSION}" \
  --install-location "/" \
  --sign "${INSTALLER_CERT}" \
  "${PKG_PATH}"

if [[ -n "${APPLE_ID_VALUE}" && -n "${APPLE_PASSWORD}" && -n "${TEAM_ID}" ]]; then
  xcrun notarytool submit "${PKG_PATH}" \
    --apple-id "${APPLE_ID_VALUE}" \
    --password "${APPLE_PASSWORD}" \
    --team-id "${TEAM_ID}" \
    --wait
  xcrun stapler staple "${PKG_PATH}"
elif [[ -n "${ASC_KEY_ID_VALUE}" && -n "${ASC_ISSUER_ID_VALUE}" && -n "${ASC_KEY_PATH_VALUE}" ]]; then
  xcrun notarytool submit "${PKG_PATH}" \
    --key "${ASC_KEY_PATH_VALUE}" \
    --key-id "${ASC_KEY_ID_VALUE}" \
    --issuer "${ASC_ISSUER_ID_VALUE}" \
    --wait
  xcrun stapler staple "${PKG_PATH}"
else
  echo "Notarization skipped. Set either APPLE_ID/APPLE_APP_PASSWORD/APPLE_TEAM_ID or ASC_KEY_ID/ASC_ISSUER_ID/ASC_KEY_PATH."
fi

/usr/sbin/spctl -a -vv -t install "${PKG_PATH}" || true
pkgutil --check-signature "${PKG_PATH}" || true

echo ""
echo "Built package:"
echo "${PKG_PATH}"
