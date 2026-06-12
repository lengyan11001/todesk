#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

if command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN="python3"
elif command -v python >/dev/null 2>&1; then
  PYTHON_BIN="python"
else
  echo "python3/python is required to build the macOS package." >&2
  exit 1
fi

"${PYTHON_BIN}" "${ROOT_DIR}/build-macos-unsigned-package.py"
