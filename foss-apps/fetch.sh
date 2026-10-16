#!/usr/bin/env bash
#
# Downloads the APK pinned in <app>/app.lock, checks its sha256 and
# regenerates the files the build needs:
#
#   <app>/<name>.apk          untouched upstream APK (git LFS)
#
# app.lock may say modified=<what the build changes>, for NOTICE.txt.
#   <app>/NOTICE.txt          license + where the source is
#   <app>/<name>.yml          package and version_code updated (the rest, the
#                             setup's description and so on, is written by hand)
#
# usage: fetch.sh <app folder>...   (no argument: every app folder)

set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

die() { echo "error: $*" >&2; exit 1; }

# yml_set <file> <package> <version_code> ; sets both in an AUTOAPK .yml, making the
# file if there is none yet
yml_set() {
	[ -f "$1" ] || : >"$1"
	sed -i -e '/^package:/d' -e '/^version_code:/d' "$1"
	{
		grep '^#' "$1" || true
		echo "package: $2"
		echo "version_code: $3"
		grep -v '^#' "$1" || true
	} >"$1.new"
	mv "$1.new" "$1"
}

fetch_app() {
	local dir=$1 lock=$1/app.lock
	local name='' package='' version_code='' version_name='' spdx='' sha256=''
	local apk_url='' source_url='' license_url='' published_by='' modified='' key value apk lname

	[ -f "$lock" ] || die "$dir: no app.lock"
	while IFS='=' read -r key value; do
		case $key in
			''|\#*) ;;
			name|package|version_code|version_name|spdx|sha256|apk_url|source_url|license_url|published_by|modified)
				printf -v "$key" '%s' "$value" ;;
			*) die "$lock: unknown key $key" ;;
		esac
	done <"$lock"
	for key in name package version_code version_name spdx sha256 apk_url source_url license_url published_by; do
		[ -n "${!key}" ] || die "$lock: $key missing"
	done

	apk=$dir/$name.apk
	if [ -f "$apk" ] && echo "$sha256  $apk" | sha256sum -c - >/dev/null 2>&1; then
		echo ">> $name $version_name already up to date"
	else
		echo ">> $name $version_name"
		curl -fsSL -o "$apk.part" "$apk_url" || { rm -f "$apk.part"; die "$name: download failed"; }
		echo "$sha256  $apk.part" | sha256sum -c - >/dev/null || { rm -f "$apk.part"; die "$name: checksum mismatch"; }
		mv "$apk.part" "$apk"
	fi

	{
		echo "$name $version_name ($package, versionCode $version_code) - $spdx"
		echo
		if [ -n "$modified" ]; then
			echo "Published by $published_by (sha256 $sha256)."
			echo "Changed for andr36oid: $modified"
		else
			echo "Shipped unmodified as published by $published_by (sha256 $sha256)."
		fi
		echo "Download: $apk_url"
		echo "Source: $source_url"
		echo
		echo "================================================================================"
		curl -fsSL "$license_url" || die "$name: could not fetch license text"
	} >"$dir/NOTICE.txt.new"
	mv "$dir/NOTICE.txt.new" "$dir/NOTICE.txt"

	yml_set "$dir/$name.yml" "$package" "$version_code"
}

if [ $# -eq 0 ]; then
	for d in "$HERE"/*/; do
		[ -f "$d/app.lock" ] && fetch_app "${d%/}"
	done
else
	for d in "$@"; do
		fetch_app "$(cd "$d" && pwd)"
	done
fi
