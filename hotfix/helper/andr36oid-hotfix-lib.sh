#!/system/bin/sh
# Sourced before hotfix.sh. Bind sources stay on /data until the next start.
# HOTFIX_ROOT is a fake root for the host test.

hotfix_log() {
	log -t andr36oid-hotfix "$HOTFIX_NAME: $*"
	printf '%s: %s\n' "$HOTFIX_NAME" "$*"
}

hotfix_bind() {
	local src target file owner mode context r=${HOTFIX_ROOT:-}
	[ "$#" = 2 ] || return 1
	case $1 in /*) src=$1 ;; *) src=$HOTFIX_DIR/$1 ;; esac
	src=$(readlink -f "$src") || return 1
	case $src in "$HOTFIX_DIR"/*) ;; *) hotfix_log "source is outside the hotfix"; return 1 ;; esac
	[ -f "$src" ] || { hotfix_log "source is not a regular file: $1"; return 1; }
	case $2 in /system/*|/vendor/*) ;; *) hotfix_log "target must be in /system or /vendor"; return 1 ;; esac
	if [ -L "$r$2" ] || [ ! -f "$r$2" ]; then
		hotfix_log "target is not a regular file: $2"
		return 1
	fi
	target=$(readlink -f "$r$2") || return 1
	case $target in "$r"/system/*|"$r"/vendor/*) ;; *) hotfix_log "target resolves outside /system and /vendor"; return 1 ;; esac
	owner=$(stat -c '%u:%g' "$target") || return 1
	mode=$(stat -c '%a' "$target") || return 1
	context=$(stat -c '%C' "$target") || return 1
	file=$(mktemp "$HOTFIX_STORE/file.XXXXXX") || return 1
	if cp "$src" "$file" && chown "$owner" "$file" && chmod "$mode" "$file" &&
		chcon "$context" "$file" && mount -o bind "$file" "$target"; then
		hotfix_log "bound $1 over $2"
		return 0
	fi
	rm -f "$file"
	hotfix_log "could not bind $1 over $2"
	return 1
}
