#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${JAVA_HOME:?Set JAVA_HOME to a full JDK 17+ installation}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK installation directory}"
PYTHON_BIN=$(command -v python3 || true)
if [ -z "$PYTHON_BIN" ]; then
  echo "[ERROR] Python 3.9+ is required (the Windows build.bat can bootstrap it automatically)." >&2
  exit 2
fi
"$PYTHON_BIN" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3,9) else 1)' || {
  echo "[ERROR] Python 3.9+ is required." >&2
  exit 2
}
exec "$PYTHON_BIN" "$ROOT/build.py" "$@"
