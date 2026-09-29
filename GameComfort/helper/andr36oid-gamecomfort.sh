#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-gamecomfort: reads files for "Continue playing" (Game comfort app). The app
# runs as the system user, which can't read RetroArch's history and config or PPSSPP's
# ppsspp.ini (they are on the internal storage and in RetroArch's private folder). This
# only ever reads: it copies small text files for the app and tells times and sizes.
# The app decides everything else.
#
# The app writes $DIR/request and starts us with ctl.start. One TAB separated request
# per line:
#   read <path>     copy the file (4 MB at most) to $DIR/f<n>
#   stat <path>     time and size of the file
#   dir <path>      folders for "prefix": looks in them and one level down
#   prefix <text>   file names starting with <text> in the "dir" folders
# $DIR/out, one TAB separated line per file that is there:
#   found <mtime> <size> <path> <n>   (read; the text is in $DIR/f<n>)
#   stat <mtime> <size> <path>
#   file <mtime> <size> <path>        (dir + prefix)
# $DIR/status is one line: "running", "done" or "error <code>".
#
# Paths are the ones root sees, and only these: the internal storage (/data/media/0),
# SD cards and USB drives (/mnt/media_rw) and RetroArch's own folder. Nothing with
# "." or ".." in it. GAMECOMFORT_DIR and GAMECOMFORT_ROOT (put in front of every path
# we look at) are for testing on a PC.

DIR=${GAMECOMFORT_DIR:-/data/misc/andr36oid-gamecomfort}
ROOT=${GAMECOMFORT_ROOT:-}
MAX_BYTES=4194304
TAB=$(printf '\t')

log() {
	[ -x /system/bin/log ] && /system/bin/log -t andr36oid-gamecomfort "$*"
	return 0
}

# own <file> ; our files in $DIR belong to the app
own() {
	[ -n "$GAMECOMFORT_DIR" ] && return 0
	chown system:system "$1" 2>/dev/null
	chmod 0640 "$1" 2>/dev/null
}

status() {
	echo "$*" >"$DIR/status.tmp"
	own "$DIR/status.tmp"
	mv -f "$DIR/status.tmp" "$DIR/status"
}

# allowed <path> ; one of the places we may read, no way out of them
allowed() {
	case $1 in
	*/./* | */. | */../* | */.. | *"$TAB"*) return 1 ;;
	/data/media/0/* | /mnt/media_rw/?*/* | /data/user/0/com.retroarch/*) return 0 ;;
	esac
	return 1
}

# info <path> ; "<mtime><TAB><size>" of a regular file, nothing if there is none
info() {
	local f="$ROOT$1" t s
	[ -f "$f" ] || return 1
	t=$(stat -c %Y "$f" 2>/dev/null) || return 1
	s=$(stat -c %s "$f" 2>/dev/null) || return 1
	printf '%s\t%s' "$t" "$s"
}

# one_dir <dir> ; the matching files right in <dir>
one_dir() {
	local d="$1" name p i
	# shellcheck disable=SC2012 # a name with a newline in it just won't match
	ls -1A "$ROOT$d" 2>/dev/null | while IFS= read -r name; do
		[ -n "$name" ] || continue
		while IFS= read -r p; do
			case $name in
			"$p"*)
				i=$(info "$d/$name") && printf 'file\t%s\t%s\n' "$i" "$d/$name"
				break
				;;
			esac
		done <"$DIR/prefixes"
	done
}

serve() {
	local kind path n=0 i sub
	: >"$DIR/dirs"
	: >"$DIR/prefixes"
	rm -f "$DIR"/f[0-9]*
	while IFS="$TAB" read -r kind path; do
		[ -n "$path" ] || continue
		if [ "$kind" = prefix ]; then
			case $path in
			*/*) ;;
			*) printf '%s\n' "$path" >>"$DIR/prefixes" ;;
			esac
			continue
		fi
		allowed "$path" || continue
		case $kind in
		read)
			i=$(info "$path") || continue
			[ "${i#*"$TAB"}" -le "$MAX_BYTES" ] || continue
			n=$((n + 1))
			cat "$ROOT$path" >"$DIR/f$n" 2>/dev/null || continue
			own "$DIR/f$n"
			printf 'found\t%s\t%s\t%s\n' "$i" "$path" "$n"
			;;
		stat)
			i=$(info "$path") && printf 'stat\t%s\t%s\n' "$i" "$path"
			;;
		dir)
			printf '%s\n' "$path" >>"$DIR/dirs"
			;;
		esac
	done <"$DIR/request"
	[ -s "$DIR/prefixes" ] || return 0
	while IFS= read -r path; do
		[ -d "$ROOT$path" ] || continue
		one_dir "$path"
		# shellcheck disable=SC2012
		ls -1A "$ROOT$path" 2>/dev/null | while IFS= read -r sub; do
			[ -n "$sub" ] && [ -d "$ROOT$path/$sub" ] && one_dir "$path/$sub"
		done
	done <"$DIR/dirs"
}

mkdir -p "$DIR"
if [ ! -f "$DIR/request" ]; then
	log "started without a request"
	status "error no_request"
	exit 1
fi
status "running"
serve >"$DIR/out.tmp"
own "$DIR/out.tmp"
mv -f "$DIR/out.tmp" "$DIR/out"
rm -f "$DIR/request" "$DIR/dirs" "$DIR/prefixes"
status "done"
exit 0
