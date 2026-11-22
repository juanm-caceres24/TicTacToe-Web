#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${PROJECT_ROOT}"

usage() {
    cat <<'EOF'
Usage: ./scripts/build.sh [--clean] [--skip-tests] [--help]

Compile and package TicTacToe-Web with strict compiler warnings (-Xlint:all -Werror).

Options:
  --clean       Run 'mvn clean' before packaging.
  --skip-tests  Compile test sources without executing tests.
  -h, --help    Show this help message and exit.
EOF
}

CLEAN=""
SKIP_TESTS=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --clean)
            CLEAN="clean"
            shift
            ;;
        --skip-tests)
            SKIP_TESTS="-DskipTests"
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

echo "[build] Building TicTacToe-Web..."
mvn ${CLEAN} package ${SKIP_TESTS}
echo "[build] Build succeeded with 0 warnings."
