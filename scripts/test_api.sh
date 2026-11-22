#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${PROJECT_ROOT}"

usage() {
    cat <<'EOF'
Usage: ./scripts/test_api.sh [--port <port>] [--help]

Starts the packaged shaded server JAR on a temporary port (with --no-public-ip),
runs HTTP smoke tests against /, /api/health, and /api/ai/status using curl,
and shuts the server down cleanly.

Options:
  --port <port>  Port to bind during smoke test (default: 18089)
  -h, --help     Show this help message and exit
EOF
}

PORT=18089
while [[ $# -gt 0 ]]; do
    case "$1" in
        --port)
            PORT="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Error: Unknown option '$1'" >&2
            usage >&2
            exit 1
            ;;
    esac
done

JAR_PATH="$(ls -1 target/tictactoe-web-*-shaded.jar 2>/dev/null | head -n 1 || true)"
if [[ -z "${JAR_PATH}" ]]; then
    echo "[test_api] Building shaded JAR..."
    mvn -q package -DskipTests
    JAR_PATH="$(ls -1 target/tictactoe-web-*-shaded.jar 2>/dev/null | head -n 1 || true)"
fi

LOG_FILE="$(mktemp)"
SERVER_PID=""
cleanup() {
    if [[ -n "${SERVER_PID}" ]] && kill -0 "${SERVER_PID}" 2>/dev/null; then
        kill "${SERVER_PID}" 2>/dev/null || true
        wait "${SERVER_PID}" 2>/dev/null || true
    fi
    rm -f "${LOG_FILE}"
}
trap cleanup EXIT

echo "[test_api] Verifying --help flag..."
java -jar "${JAR_PATH}" --help >/dev/null

echo "[test_api] Starting server on 127.0.0.1:${PORT}..."
java -jar "${JAR_PATH}" --host 127.0.0.1 --port "${PORT}" --no-public-ip >"${LOG_FILE}" 2>&1 &
SERVER_PID=$!

READY=0
for _ in $(seq 1 40); do
    if curl -fsS "http://127.0.0.1:${PORT}/api/health" >/dev/null 2>&1; then
        READY=1
        break
    fi
    sleep 0.25
done

if [[ "${READY}" -ne 1 ]]; then
    echo "[test_api] Server failed to start. Log output:" >&2
    cat "${LOG_FILE}" >&2
    exit 1
fi

echo "[test_api] Startup banner:"
cat "${LOG_FILE}"

echo "[test_api] Checking GET / , /styles.css , and /app.js ..."
ROOT_HTML="$(curl -fsS "http://127.0.0.1:${PORT}/")"
echo "${ROOT_HTML}" | grep -q "Mobile Tic-Tac-Toe"
curl -fsS "http://127.0.0.1:${PORT}/styles.css" | grep -q "board-wrapper"
curl -fsS "http://127.0.0.1:${PORT}/app.js" | grep -q "JOIN_LOBBY"

echo "[test_api] Checking path traversal protection ..."
TRAVERSAL_CODE="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/..%2Fpom.xml")"
if [[ "${TRAVERSAL_CODE}" != "400" && "${TRAVERSAL_CODE}" != "404" ]]; then
    echo "[test_api] Unexpected status for path traversal check: ${TRAVERSAL_CODE}" >&2
    exit 1
fi

echo "[test_api] Checking GET /api/health ..."
HEALTH_JSON="$(curl -fsS "http://127.0.0.1:${PORT}/api/health")"
echo "  -> ${HEALTH_JSON}"
echo "${HEALTH_JSON}" | grep -q '"status":"ok"'

echo "[test_api] Checking GET /api/ai/status ..."
AI_JSON="$(curl -fsS "http://127.0.0.1:${PORT}/api/ai/status")"
echo "  -> ${AI_JSON}"
echo "${AI_JSON}" | grep -q '"available":'

echo "[test_api] All HTTP smoke checks passed."
