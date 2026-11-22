#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${PROJECT_ROOT}"

usage() {
    cat <<'EOF'
Usage: ./scripts/test.sh [--clean] [--help]

Compile and run the full JUnit 5 test suite for TicTacToe-Web.

Options:
  --clean     Run 'mvn clean test' for a full rebuild before testing.
  -h, --help  Show this help message and exit.
EOF
}

CLEAN=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --clean)
            CLEAN="clean"
            shift
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

echo "[test] Running TicTacToe-Web test suite..."
mvn ${CLEAN} test
echo "[test] All tests passed."
