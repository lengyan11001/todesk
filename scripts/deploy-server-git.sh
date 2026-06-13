#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/lengyan11001/todesk.git}"
BRANCH="${BRANCH:-main}"
GIT_DEPTH="${GIT_DEPTH:-1}"
CHECKOUT_DIR="${CHECKOUT_DIR:-/home/ubuntu/bhzn-todesk-git}"
APP_DIR="${APP_DIR:-/home/ubuntu/bhzn-todesk}"
BACKUP_ROOT="${BACKUP_ROOT:-${APP_DIR}/deploy-backups}"
LOG_DIR="${LOG_DIR:-/home/ubuntu/bhzn-todesk/logs}"
PID_FILE="${PID_FILE:-${APP_DIR}/server.pid}"
NODE_BIN="${NODE_BIN:-/usr/bin/node}"
SERVICE_NAME="${SERVICE_NAME:-bhzn-todesk-server}"
TURN_ENV_FILE="${TURN_ENV_FILE:-/etc/bhzn-turn/server.env}"

timestamp="$(date +%Y%m%d-%H%M%S)"
backup_dir="${BACKUP_ROOT}/${timestamp}-git-deploy"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

current_server_pid() {
  if [ -f "${PID_FILE}" ]; then
    local pid
    pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
    if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
      echo "${pid}"
      return
    fi
  fi
  pgrep -f "${NODE_BIN} ${APP_DIR}/src/server.js" | head -1 || true
}

env_from_pid() {
  local pid="$1"
  local key="$2"
  if [ -n "${pid}" ] && [ -r "/proc/${pid}/environ" ]; then
    tr '\0' '\n' <"/proc/${pid}/environ" | awk -F= -v key="${key}" '$1 == key { sub(/^[^=]*=/, ""); print; exit }'
  fi
}

validate_runtime_env() {
  if [ -e "${TURN_ENV_FILE}" ] && [ ! -r "${TURN_ENV_FILE}" ]; then
    echo "Runtime env exists but is not readable by $(id -un): ${TURN_ENV_FILE}" >&2
    echo "Fix permissions before deploy, for example: sudo chgrp $(id -gn) ${TURN_ENV_FILE} && sudo chmod 640 ${TURN_ENV_FILE}" >&2
    exit 1
  fi
}

stop_server() {
  local pid="$1"
  if [ -z "${pid}" ]; then
    return
  fi
  if ! kill -0 "${pid}" 2>/dev/null; then
    return
  fi
  echo "Stopping ${SERVICE_NAME} pid=${pid}"
  kill "${pid}" 2>/dev/null || true
  for _ in $(seq 1 30); do
    if ! kill -0 "${pid}" 2>/dev/null; then
      return
    fi
    sleep 1
  done
  echo "Force stopping ${SERVICE_NAME} pid=${pid}"
  kill -9 "${pid}" 2>/dev/null || true
}

start_server() {
  mkdir -p "${LOG_DIR}"
  cd "${APP_DIR}"
  export PORT="${PORT:-38080}"
  export HOST="${HOST:-0.0.0.0}"
  if [ -e "${TURN_ENV_FILE}" ]; then
    validate_runtime_env
    set -a
    # shellcheck disable=SC1090
    . "${TURN_ENV_FILE}"
    set +a
  fi
  nohup "${NODE_BIN}" "${APP_DIR}/src/server.js" >>"${LOG_DIR}/server.out.log" 2>>"${LOG_DIR}/server.err.log" &
  echo "$!" >"${PID_FILE}"
  sleep 1
  if ! kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
    echo "Server failed to start; recent stderr:" >&2
    tail -80 "${LOG_DIR}/server.err.log" >&2 || true
    exit 1
  fi
  echo "Started ${SERVICE_NAME} pid=$(cat "${PID_FILE}") PORT=${PORT} HOST=${HOST}"
}

require_cmd git
require_cmd rsync
require_cmd npm
require_cmd "${NODE_BIN}"
validate_runtime_env

old_pid="$(current_server_pid)"
if [ -n "${old_pid}" ]; then
  PORT="${PORT:-$(env_from_pid "${old_pid}" PORT || true)}"
  HOST="${HOST:-$(env_from_pid "${old_pid}" HOST || true)}"
  ADMIN_TOKEN="${ADMIN_TOKEN:-$(env_from_pid "${old_pid}" ADMIN_TOKEN || true)}"
  DEFAULT_ADMIN_USERNAME="${DEFAULT_ADMIN_USERNAME:-$(env_from_pid "${old_pid}" DEFAULT_ADMIN_USERNAME || true)}"
  DEFAULT_ADMIN_PASSWORD="${DEFAULT_ADMIN_PASSWORD:-$(env_from_pid "${old_pid}" DEFAULT_ADMIN_PASSWORD || true)}"
fi
export PORT="${PORT:-38080}"
export HOST="${HOST:-0.0.0.0}"
[ -n "${ADMIN_TOKEN:-}" ] && export ADMIN_TOKEN
[ -n "${DEFAULT_ADMIN_USERNAME:-}" ] && export DEFAULT_ADMIN_USERNAME
[ -n "${DEFAULT_ADMIN_PASSWORD:-}" ] && export DEFAULT_ADMIN_PASSWORD

if [ -d "${CHECKOUT_DIR}/.git" ]; then
  echo "Updating checkout ${CHECKOUT_DIR}"
  git -C "${CHECKOUT_DIR}" fetch --depth "${GIT_DEPTH}" origin "${BRANCH}"
  git -C "${CHECKOUT_DIR}" checkout "${BRANCH}"
  git -C "${CHECKOUT_DIR}" reset --hard "origin/${BRANCH}"
else
  echo "Cloning ${REPO_URL} -> ${CHECKOUT_DIR}"
  git clone --depth "${GIT_DEPTH}" --branch "${BRANCH}" "${REPO_URL}" "${CHECKOUT_DIR}"
fi

mkdir -p "${backup_dir}"
for item in package.json package-lock.json src public; do
  if [ -e "${APP_DIR}/${item}" ]; then
    rsync -a "${APP_DIR}/${item}" "${backup_dir}/"
  fi
done
echo "Backup saved to ${backup_dir}"

mkdir -p "${APP_DIR}"
rsync -a --delete \
  --exclude 'node_modules/' \
  --exclude 'data/' \
  --exclude 'public/downloads/' \
  --exclude 'deploy-backups/' \
  --exclude '__upload_tmp/' \
  --exclude 'logs/' \
  --exclude 'server.pid' \
  "${CHECKOUT_DIR}/server/" "${APP_DIR}/"

cd "${APP_DIR}"
npm install --omit=dev

stop_server "${old_pid}"
start_server

echo "Health check:"
curl -fsS "http://127.0.0.1:${PORT}/api/health"
echo
