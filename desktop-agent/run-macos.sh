#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -x ".venv/bin/python" ]; then
  python3 -m venv .venv
fi

if [ ! -f ".venv/.deps-installed" ] || [ "requirements.txt" -nt ".venv/.deps-installed" ]; then
  .venv/bin/python -m pip install --upgrade pip
  .venv/bin/python -m pip install -r requirements.txt
  touch .venv/.deps-installed
fi

if [ -f "agent.payload" ] && [ -f "run_payload_macos.py" ]; then
  exec .venv/bin/python run_payload_macos.py "$@"
fi

exec .venv/bin/python bhzn_desktop_agent.py "$@"
