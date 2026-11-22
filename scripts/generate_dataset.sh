#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${PROJECT_ROOT}"

for arg in "$@"; do
    if [[ "$arg" == "-h" || "$arg" == "--help" ]]; then
        if [[ ! -d target/classes ]]; then
            mvn -q compile
        fi
        exec java -cp target/classes com.tictactoe.DatasetGenMain --help
    fi
done

if [[ ! -d target/classes ]]; then
    echo "[dataset] Compiling classes..."
    mvn -q compile
fi

exec java -cp target/classes com.tictactoe.DatasetGenMain "$@"
