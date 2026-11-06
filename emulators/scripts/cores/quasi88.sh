#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/ndk-make.sh"
build_ndk_make "$1" "$2" Makefile
