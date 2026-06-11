import hashlib
import os
import secrets
import shutil
import stat
import zipfile
from io import BytesIO
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


ROOT_DIR = Path(__file__).resolve().parent
DIST_DIR = ROOT_DIR / "dist-macos-unsigned"
PACKAGE_DIR = DIST_DIR / "BHZN-ToDesk-Agent-mac"
ZIP_PATH = DIST_DIR / "BHZN-ToDesk-Agent-mac.zip"
MAGIC = b"BHZNPAY1"
KEY_SALT = b"bhzn-todesk-desktop-agent-v1"
KEY_PARTS = ("BHZN", "ToDesk", "Desktop", "Agent", "2026")
EXECUTABLE_NAMES = {
    "Install.command",
    "Show-ID.command",
    "install-macos.sh",
    "run-macos.sh",
    "uninstall-macos.sh",
}


def payload_key() -> bytes:
    material = "|".join(KEY_PARTS).encode("utf-8")
    return hashlib.sha256(KEY_SALT + material).digest()


def write_encrypted_payload() -> None:
    archive_bytes = BytesIO()
    with zipfile.ZipFile(archive_bytes, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(ROOT_DIR / "bhzn_desktop_agent.py", "bhzn_desktop_agent.py")

    nonce = secrets.token_bytes(12)
    ciphertext = AESGCM(payload_key()).encrypt(nonce, archive_bytes.getvalue(), None)
    (PACKAGE_DIR / "agent.payload").write_bytes(MAGIC + nonce + ciphertext)


def copy_package_files() -> None:
    files = {
        "run_payload_macos.py": "run_payload_macos.py",
        "requirements.txt": "requirements.txt",
        "run-macos.sh": "run-macos.sh",
        "install-macos.sh": "install-macos.sh",
        "uninstall-macos.sh": "uninstall-macos.sh",
        "MACOS_UNSIGNED.md": "README.md",
    }
    for source, target in files.items():
        shutil.copy2(ROOT_DIR / source, PACKAGE_DIR / target)

    install_command = """#!/usr/bin/env bash
set -euo pipefail
PACKAGE_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${PACKAGE_DIR}"
exec /usr/bin/env bash "${PACKAGE_DIR}/install-macos.sh"
"""
    show_id_command = """#!/usr/bin/env bash
set -euo pipefail
PACKAGE_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${PACKAGE_DIR}"
if [ -x "${HOME}/Applications/BHZN-ToDesk-Agent/run-macos.sh" ]; then
  exec "${HOME}/Applications/BHZN-ToDesk-Agent/run-macos.sh" --show-id
fi
APP_BIN="${HOME}/Applications/BHZN ToDesk Agent.app/Contents/MacOS/BHZN ToDesk Agent"
if [ -x "${APP_BIN}" ]; then
  exec "${APP_BIN}" --show-id
fi
exec /usr/bin/env bash "${PACKAGE_DIR}/run-macos.sh" --show-id
"""
    (PACKAGE_DIR / "Install.command").write_text(install_command, encoding="utf-8", newline="\n")
    (PACKAGE_DIR / "Show-ID.command").write_text(show_id_command, encoding="utf-8", newline="\n")

    for name in EXECUTABLE_NAMES:
        path = PACKAGE_DIR / name
        path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def add_to_zip(archive: zipfile.ZipFile, path: Path, arcname: str) -> None:
    info = zipfile.ZipInfo(arcname.replace(os.sep, "/"))
    info.date_time = (2026, 6, 11, 0, 0, 0)
    mode = 0o755 if path.name in EXECUTABLE_NAMES else 0o644
    if path.is_dir():
        info.external_attr = (0o755 | stat.S_IFDIR) << 16
        archive.writestr(info, b"")
        return
    info.external_attr = (mode | stat.S_IFREG) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    archive.writestr(info, path.read_bytes())


def make_zip() -> None:
    if ZIP_PATH.exists():
        ZIP_PATH.unlink()
    with zipfile.ZipFile(ZIP_PATH, "w") as archive:
        root_arc = PACKAGE_DIR.name
        add_to_zip(archive, PACKAGE_DIR, f"{root_arc}/")
        for path in sorted(PACKAGE_DIR.rglob("*")):
            arcname = f"{root_arc}/{path.relative_to(PACKAGE_DIR)}"
            add_to_zip(archive, path, arcname)


def main() -> int:
    if DIST_DIR.exists():
        shutil.rmtree(DIST_DIR)
    PACKAGE_DIR.mkdir(parents=True)

    write_encrypted_payload()
    copy_package_files()
    make_zip()

    print("Built unsigned internal macOS package:")
    print(ZIP_PATH)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
