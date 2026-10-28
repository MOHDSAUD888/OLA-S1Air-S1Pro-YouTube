#!/usr/bin/env bash
# Open Android Settings on an Ola S1 head unit (Linux / macOS).
#
# Usage:
#   ./open-settings.sh fe80::1234:5678:9abc:def0%6
#   ./open-settings.sh 2001:db8::1
#   ./open-settings.sh fe80::1%6 --diag
#   ./open-settings.sh fe80::1%6 --home
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OLA="$HERE/ola.py"

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <ipv6> [--diag|--home] [--scope N]" >&2
    exit 2
fi

ADDR="$1"; shift
ACTION="settings"
SCOPE_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --diag)  ACTION="diag" ;;
        --home)  ACTION="home" ;;
        --scope) SCOPE_ARGS=(--scope "$2"); shift ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

PY="${PYTHON:-python3}"
if ! "$PY" -c "import grpc" >/dev/null 2>&1; then
    echo "Installing grpcio (one time) ..."
    "$PY" -m pip install -r "$HERE/requirements.txt"
fi

exec "$PY" "$OLA" "$ACTION" --ipv6 "$ADDR" "${SCOPE_ARGS[@]}"
