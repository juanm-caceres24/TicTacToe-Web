#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${PROJECT_ROOT}"

find_shaded_jar() {
    ls -1 target/tictactoe-web-*-shaded.jar 2>/dev/null | head -n 1 || true
}

JAR_PATH="$(find_shaded_jar)"
if [[ -z "${JAR_PATH}" ]]; then
    echo "[run] Shaded JAR not found. Building project..."
    mvn -q package -DskipTests
    JAR_PATH="$(find_shaded_jar)"
fi

if [[ -z "${JAR_PATH}" ]]; then
    echo "Error: Failed to locate shaded JAR in target/" >&2
    exit 1
fi

exec java -jar "${JAR_PATH}" "$@"
