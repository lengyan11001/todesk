import hashlib
import runpy
import sys
import tempfile
import zipfile
from io import BytesIO
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


MAGIC = b"BHZNPAY1"
KEY_SALT = b"bhzn-todesk-desktop-agent-v1"
KEY_PARTS = (
    "BHZN",
    "ToDesk",
    "Desktop",
    "Agent",
    "2026",
)


def payload_key() -> bytes:
    material = "|".join(KEY_PARTS).encode("utf-8")
    return hashlib.sha256(KEY_SALT + material).digest()


def decrypt_payload(payload_path: Path) -> bytes:
    raw = payload_path.read_bytes()
    if len(raw) < len(MAGIC) + 12 + 16 or not raw.startswith(MAGIC):
        raise RuntimeError("Invalid agent payload.")
    nonce_start = len(MAGIC)
    nonce = raw[nonce_start : nonce_start + 12]
    ciphertext = raw[nonce_start + 12 :]
    return AESGCM(payload_key()).decrypt(nonce, ciphertext, None)


def main() -> int:
    base_dir = Path(__file__).resolve().parent
    payload_path = base_dir / "agent.payload"
    if not payload_path.exists():
        raise RuntimeError(f"Missing encrypted payload: {payload_path}")

    archive = decrypt_payload(payload_path)
    with tempfile.TemporaryDirectory(prefix="bhzn-todesk-agent-") as temp_dir:
        temp_path = Path(temp_dir)
        with zipfile.ZipFile(BytesIO(archive), "r") as package:
            package.extractall(temp_path)
        agent_path = temp_path / "bhzn_desktop_agent.py"
        if not agent_path.exists():
            raise RuntimeError("Encrypted payload does not contain the agent entrypoint.")
        sys.argv = [str(agent_path)] + sys.argv[1:]
        runpy.run_path(str(agent_path), run_name="__main__")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
