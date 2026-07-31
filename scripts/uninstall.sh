#!/usr/bin/env bash
# Anonymous — Linux uninstaller (thin wrapper around install.sh --uninstall).
set -euo pipefail
SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
exec "$SCRIPT_DIR/install.sh" --uninstall "$@"
