#!/bin/bash
# Refresh from the current approved PNGs before the Android source snapshot/build.
# No generator invocation, redraw, quantization or fixed source hashes.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
exec python3 "$here/build-loading.py" \
  --png-dir "${ANDR36OID_LOADING_PNG_DIR:-/root/r36splash/blueprint}" \
  --sizes "${ANDR36OID_LOADING_SIZES:-all}" "$@"
