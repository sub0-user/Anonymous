#!/usr/bin/env bash
# Anonymous — Linux installer (Ubuntu, Debian, and other distros).
set -euo pipefail

APP_NAME="Anonymous"
APP_DIR_NAME="anonymous"
DATA_DIR_NAME=".anonymous"

log()  { printf '\033[1;34m[install]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[install] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# Resolve this script's real location (follows symlinks).
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
  SOURCE="$(readlink "$SOURCE")"
  [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
done
SCRIPT_DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

usage() {
  cat <<EOF
Usage: $0 [options]
  --zip PATH       App zip (default: auto-detect build/distributions/app-*.zip)
  --prefix PATH    Install dir (default: \$HOME/.local/share/$APP_DIR_NAME)
  --bin-dir PATH   Launcher symlink dir (default: \$HOME/.local/bin)
  --uninstall      Remove the installation (keeps ~/$DATA_DIR_NAME)
  --no-desktop     Skip the .desktop entry
  -h, --help       This help
EOF
}

ZIP=""; PREFIX=""; BIN_DIR=""; CREATE_DESKTOP=1; UNINSTALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --zip) ZIP="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    --bin-dir) BIN_DIR="$2"; shift 2 ;;
    --no-desktop) CREATE_DESKTOP=0; shift ;;
    --uninstall) UNINSTALL=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1 (try --help)" ;;
  esac
done

PREFIX="${PREFIX:-$HOME/.local/share/$APP_DIR_NAME}"
BIN_DIR="${BIN_DIR:-$HOME/.local/bin}"

uninstall() {
  log "Removing $PREFIX"
  rm -rf "$PREFIX"
  rm -f "$BIN_DIR/$APP_DIR_NAME" "$BIN_DIR/anonymous"
  rm -f "$HOME/.local/share/applications/anonymous.desktop"
  log "Uninstalled. Your chat data in ~/$DATA_DIR_NAME was kept."
}

if [ "$UNINSTALL" = "1" ]; then uninstall; exit 0; fi

if [ -z "$ZIP" ]; then
  ZIP="$(ls "$PROJECT_ROOT"/build/distributions/app-*.zip 2>/dev/null | head -n1 || true)"
fi
[ -n "$ZIP" ] || fail "no app zip found — build it first: ./gradlew jlinkZip  (or pass --zip)"
[ -f "$ZIP" ] || fail "zip not found: $ZIP"
command -v unzip >/dev/null 2>&1 || fail "unzip is required (e.g. sudo apt install unzip)"

mkdir -p "$PREFIX" "$BIN_DIR"
log "Extracting $(basename "$ZIP") -> $PREFIX"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$ZIP" -d "$TMP"
INNER="$(find "$TMP" -mindepth 1 -maxdepth 1 -type d | head -n1)"
[ -n "$INNER" ] || fail "zip did not contain an app image"
rm -rf "$PREFIX"/*
cp -r "$INNER"/. "$PREFIX"/

chmod +x "$PREFIX/bin/anonymous" 2>/dev/null || true
# The zip may not preserve exec bits for the runtime's spawn helper — without it,
# ProcessBuilder fails with "posix_spawn ... Permission denied".
chmod +x "$PREFIX/bin/java" "$PREFIX/lib/jspawnhelper" 2>/dev/null || true
# Wrapper script, NOT a symlink: jlink launchers resolve their own path, so a symlink
# would look for java next to the symlink instead of inside the installed app image.
rm -f "$BIN_DIR/anonymous"
cat > "$BIN_DIR/anonymous" <<EOF
#!/usr/bin/env bash
exec "$PREFIX/bin/anonymous" "\$@"
EOF
chmod +x "$BIN_DIR/anonymous"

if [ "$CREATE_DESKTOP" = "1" ]; then
  mkdir -p "$HOME/.local/share/applications"
  ICON_SRC="$PROJECT_ROOT/src/main/resources/org/server/anonymous/logo/logo.png"
  if [ -f "$ICON_SRC" ]; then
    cp "$ICON_SRC" "$PREFIX/logo.png"
    ICON="$PREFIX/logo.png"
  else
    ICON=""
  fi
  {
    printf '[Desktop Entry]\n'
    printf 'Type=Application\n'
    printf 'Name=%s\n' "$APP_NAME"
    printf 'Comment=Self-hosted, Tor-based private messenger\n'
    printf 'Exec=%s/bin/anonymous\n' "$PREFIX"
    if [ -n "$ICON" ]; then printf 'Icon=%s\n' "$ICON"; fi
    printf 'Terminal=false\n'
    printf 'Categories=Network;InstantMessaging;\n'
  } > "$HOME/.local/share/applications/anonymous.desktop"
  chmod +x "$HOME/.local/share/applications/anonymous.desktop"
  update-desktop-database "$HOME/.local/share/applications" >/dev/null 2>&1 || true
fi

if ! echo ":$PATH:" | grep -q ":$BIN_DIR:"; then
  log "Add $BIN_DIR to your PATH, e.g.:  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.bashrc"
fi
log "Installed. Run it with:  anonymous"
log "Data directory: ~/$DATA_DIR_NAME  |  Uninstall: $0 --uninstall"
