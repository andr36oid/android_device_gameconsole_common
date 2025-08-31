#!/usr/bin/env bash
set -euo pipefail
TARGET_DIR="$1"

# Ensure linuxrc is executable
chmod +x "${TARGET_DIR}/linuxrc"

# Create /init symlink pointing to linuxrc
if [ ! -e "${TARGET_DIR}/init" ]; then
  ln -s ./linuxrc "${TARGET_DIR}/init"
fi

