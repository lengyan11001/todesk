#!/usr/bin/env bash
set -euo pipefail

if [ "$(uname -s)" != "Darwin" ]; then
  echo "This app package must be built on macOS." >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_VENV="${ROOT_DIR}/.build-venv-macos"
BUILD_DIR="${ROOT_DIR}/build-macos-app"
DIST_DIR="${ROOT_DIR}/dist-macos-app"
PACKAGE_DIR="${DIST_DIR}/BHZN-ToDesk-Agent-mac"
ZIP_PATH="${DIST_DIR}/BHZN-ToDesk-Agent-mac.zip"
APP_NAME="BHZN ToDesk Agent"
APP_BUNDLE="${DIST_DIR}/${APP_NAME}.app"

rm -rf "${BUILD_DIR}" "${DIST_DIR}"
mkdir -p "${PACKAGE_DIR}"

if [ ! -x "${BUILD_VENV}/bin/python" ]; then
  python3 -m venv "${BUILD_VENV}"
fi

"${BUILD_VENV}/bin/python" -m pip install --upgrade pip
"${BUILD_VENV}/bin/python" -m pip install -r "${ROOT_DIR}/requirements.txt" pyinstaller

"${BUILD_VENV}/bin/python" -m PyInstaller \
  --noconfirm \
  --clean \
  --windowed \
  --name "${APP_NAME}" \
  --osx-bundle-identifier "top.bhzn.todesk.agent" \
  --distpath "${DIST_DIR}" \
  --workpath "${BUILD_DIR}" \
  "${ROOT_DIR}/bhzn_desktop_agent.py"

/usr/bin/python3 - <<'PY' "${APP_BUNDLE}"
import plistlib
import sys
from pathlib import Path

app = Path(sys.argv[1])
plist_path = app / "Contents" / "Info.plist"
data = plistlib.loads(plist_path.read_bytes())
data.update(
    {
        "CFBundleDisplayName": "BHZN ToDesk Agent",
        "CFBundleName": "BHZN ToDesk Agent",
        "CFBundleIdentifier": "top.bhzn.todesk.agent",
        "CFBundleShortVersionString": "0.1.4",
        "CFBundleVersion": "0.1.4",
        "LSApplicationCategoryType": "public.app-category.utilities",
        "NSAppleEventsUsageDescription": "BHZN ToDesk Agent uses automation only for user-approved remote control actions.",
        "NSScreenCaptureUsageDescription": "BHZN ToDesk Agent captures the screen only after the device owner enables remote support.",
    }
)
plist_path.write_bytes(plistlib.dumps(data, sort_keys=False))
PY

if command -v codesign >/dev/null 2>&1; then
  codesign --force --deep --sign - "${APP_BUNDLE}" || true
fi

cp -R "${APP_BUNDLE}" "${PACKAGE_DIR}/${APP_NAME}.app"
cp "${ROOT_DIR}/install-macos.sh" "${PACKAGE_DIR}/install-macos.sh"
cp "${ROOT_DIR}/uninstall-macos.sh" "${PACKAGE_DIR}/uninstall-macos.sh"
cp "${ROOT_DIR}/MACOS_UNSIGNED.md" "${PACKAGE_DIR}/README.md"

cat > "${PACKAGE_DIR}/Install.command" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
PACKAGE_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${PACKAGE_DIR}"
exec /usr/bin/env bash "${PACKAGE_DIR}/install-macos.sh"
SH

cat > "${PACKAGE_DIR}/Show-ID.command" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
APP_BIN="${HOME}/Applications/BHZN ToDesk Agent.app/Contents/MacOS/BHZN ToDesk Agent"
if [ -x "${APP_BIN}" ]; then
  exec "${APP_BIN}" --show-id
fi
echo "BHZN ToDesk Agent is not installed yet."
echo "Run Install.command first."
exit 1
SH

chmod +x "${PACKAGE_DIR}/Install.command" \
  "${PACKAGE_DIR}/Show-ID.command" \
  "${PACKAGE_DIR}/install-macos.sh" \
  "${PACKAGE_DIR}/uninstall-macos.sh"

cd "${DIST_DIR}"
/usr/bin/ditto -c -k --sequesterRsrc --keepParent "BHZN-ToDesk-Agent-mac" "${ZIP_PATH}"

echo "Built macOS app package:"
echo "${ZIP_PATH}"
