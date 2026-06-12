#!/usr/bin/env bash
set -euo pipefail

if [ "$(uname -s)" != "Darwin" ]; then
  echo "This app package must be built on macOS." >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION="0.1.20"
MACOS_ARCH="${MACOS_ARCH:-$(uname -m)}"
BUILD_VENV="${ROOT_DIR}/.build-venv-macos-${MACOS_ARCH}"
BUILD_DIR="${ROOT_DIR}/build-macos-app"
DIST_DIR="${ROOT_DIR}/dist-macos-app"
ZIP_PATH="${DIST_DIR}/BHZN-ToDesk-Agent-mac.zip"
APP_NAME="BHZN ToDesk Agent"
HELPER_NAME="bhzn-agent-helper"
APP_BUNDLE="${DIST_DIR}/${APP_NAME}.app"
AGENT_BUILD_DIR="${BUILD_DIR}/agent"
AGENT_DIST_DIR="${BUILD_DIR}/agent-dist"
HELPER_DIR="${APP_BUNDLE}/Contents/Resources/agent-bin"
HELPER_EXECUTABLE="${HELPER_DIR}/${HELPER_NAME}"

case "${MACOS_ARCH}" in
  arm64|x86_64|universal2) ;;
  *)
    echo "Unsupported MACOS_ARCH=${MACOS_ARCH}; use arm64, x86_64, or universal2." >&2
    exit 1
    ;;
esac

PYTHON_CMD=(python3)
if [ "${MACOS_ARCH}" = "x86_64" ]; then
  PYTHON_CMD=(arch -x86_64 python3)
elif [ "${MACOS_ARCH}" = "arm64" ]; then
  PYTHON_CMD=(arch -arm64 python3)
fi
VENV_PYTHON_CMD=("${BUILD_VENV}/bin/python")
if [ "${MACOS_ARCH}" = "x86_64" ]; then
  VENV_PYTHON_CMD=(arch -x86_64 "${BUILD_VENV}/bin/python")
elif [ "${MACOS_ARCH}" = "arm64" ]; then
  VENV_PYTHON_CMD=(arch -arm64 "${BUILD_VENV}/bin/python")
fi

rm -rf "${BUILD_DIR}" "${DIST_DIR}"
mkdir -p "${DIST_DIR}"

if [ ! -x "${BUILD_VENV}/bin/python" ]; then
  "${PYTHON_CMD[@]}" -m venv "${BUILD_VENV}"
fi

"${VENV_PYTHON_CMD[@]}" -m pip install --upgrade pip
"${VENV_PYTHON_CMD[@]}" -m pip install --only-binary=:all: \
  Pillow==10.4.0 \
  cryptography==48.0.1 \
  cffi \
  pyobjc-core \
  pyobjc-framework-Cocoa \
  pyobjc-framework-quartz \
  pyinstaller
"${VENV_PYTHON_CMD[@]}" -m pip install \
  mss==9.0.1 \
  pyautogui==0.9.54 \
  pyperclip==1.11.0 \
  websocket-client==1.8.0 \
  numpy==2.0.2 \
  av==12.3.0 \
  aiortc==1.9.0

"${VENV_PYTHON_CMD[@]}" -m PyInstaller \
  --noconfirm \
  --clean \
  --console \
  --target-architecture "${MACOS_ARCH}" \
  --specpath "${BUILD_DIR}" \
  --collect-all aiortc \
  --collect-all aioice \
  --collect-all av \
  --exclude-module tkinter \
  --exclude-module _tkinter \
  --exclude-module tcl \
  --exclude-module tk \
  --name "${HELPER_NAME}" \
  --distpath "${AGENT_DIST_DIR}" \
  --workpath "${AGENT_BUILD_DIR}" \
  "${ROOT_DIR}/bhzn_desktop_agent.py"

mkdir -p "${APP_BUNDLE}/Contents/MacOS" \
  "${APP_BUNDLE}/Contents/Resources" \
  "${HELPER_DIR}"

cat > "${APP_BUNDLE}/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key>
  <string>en</string>
  <key>CFBundleDisplayName</key>
  <string>${APP_NAME}</string>
  <key>CFBundleExecutable</key>
  <string>${APP_NAME}</string>
  <key>CFBundleIdentifier</key>
  <string>top.bhzn.todesk.agent</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>${APP_NAME}</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>${VERSION}</string>
  <key>CFBundleVersion</key>
  <string>${VERSION}</string>
  <key>LSApplicationCategoryType</key>
  <string>public.app-category.utilities</string>
  <key>NSAppleEventsUsageDescription</key>
  <string>BHZN ToDesk Agent uses automation only for user-approved remote control actions.</string>
  <key>NSScreenCaptureUsageDescription</key>
  <string>BHZN ToDesk Agent captures the screen only after the device owner enables remote support.</string>
</dict>
</plist>
PLIST

build_launcher_for_arch() {
  local arch="$1"
  local output="$2"
  /usr/bin/swiftc \
    -O \
    -target "${arch}-apple-macos12.3" \
    -framework Cocoa \
    -framework ImageIO \
    -framework ScreenCaptureKit \
    "${ROOT_DIR}/MacAgentLauncher.swift" \
    -o "${output}"
}

if [ "${MACOS_ARCH}" = "universal2" ]; then
  build_launcher_for_arch arm64 "${BUILD_DIR}/${APP_NAME}-arm64"
  build_launcher_for_arch x86_64 "${BUILD_DIR}/${APP_NAME}-x86_64"
  /usr/bin/lipo -create \
    "${BUILD_DIR}/${APP_NAME}-arm64" \
    "${BUILD_DIR}/${APP_NAME}-x86_64" \
    -output "${APP_BUNDLE}/Contents/MacOS/${APP_NAME}"
else
  build_launcher_for_arch "${MACOS_ARCH}" "${APP_BUNDLE}/Contents/MacOS/${APP_NAME}"
fi

cp -R "${AGENT_DIST_DIR}/${HELPER_NAME}/." "${HELPER_DIR}/"
chmod +x "${APP_BUNDLE}/Contents/MacOS/${APP_NAME}" \
  "${HELPER_EXECUTABLE}"

/usr/bin/python3 - <<'PY' "${APP_BUNDLE}" "${VERSION}"
import plistlib
import sys
from pathlib import Path

app = Path(sys.argv[1])
version = sys.argv[2]
plist_path = app / "Contents" / "Info.plist"
data = plistlib.loads(plist_path.read_bytes())
data.update(
    {
        "CFBundleDisplayName": "BHZN ToDesk Agent",
        "CFBundleName": "BHZN ToDesk Agent",
        "CFBundleIdentifier": "top.bhzn.todesk.agent",
        "CFBundleShortVersionString": version,
        "CFBundleVersion": version,
        "LSApplicationCategoryType": "public.app-category.utilities",
        "NSAppleEventsUsageDescription": "BHZN ToDesk Agent uses automation only for user-approved remote control actions.",
        "NSScreenCaptureUsageDescription": "BHZN ToDesk Agent captures the screen only after the device owner enables remote support.",
    }
)
plist_path.write_bytes(plistlib.dumps(data, sort_keys=False))
PY

if command -v codesign >/dev/null 2>&1; then
  codesign --force --sign - --timestamp=none --requirements '=designated => identifier "top.bhzn.todesk.agent"' "${APP_BUNDLE}"
  codesign --verify --strict --verbose=2 "${APP_BUNDLE}"
fi

cd "${DIST_DIR}"
/usr/bin/ditto -c -k --sequesterRsrc --keepParent "${APP_NAME}.app" "${ZIP_PATH}"

echo "Built macOS app package:"
echo "${ZIP_PATH}"
