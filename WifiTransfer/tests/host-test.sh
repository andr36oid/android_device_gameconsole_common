#!/bin/bash
# Tests the Wi-Fi transfer server on a PC, without Android: builds the plain Java classes
# with javac, starts TestServer and talks to it with curl.
# Usage: tests/host-test.sh [path to a JDK bin folder]
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
APP=$(dirname "$HERE")
JDK=${1:-$(dirname "$(readlink -f "$(command -v javac)")")}
T=$(mktemp -d)
trap 'exec 3>&- 2>/dev/null; kill $PID 2>/dev/null; rm -rf "$T"' EXIT
SRC=$APP/src/org/andr36oid/wifitransfer
mkdir -p "$T/classes"
"$JDK/javac" -encoding UTF-8 -d "$T/classes" $SRC/HttpServer.java $SRC/FileApi.java \
	$SRC/Json.java $SRC/Root.java $SRC/Addresses.java \
	$HERE/org/andr36oid/wifitransfer/TestServer.java || exit 1

mkfifo "$T/in"
"$JDK/java" -Xmx24m -cp "$T/classes" org.andr36oid.wifitransfer.TestServer "$T/data" "$APP/assets" \
	< "$T/in" > "$T/out" 2> "$T/log" &
PID=$!
exec 3> "$T/in"
for i in $(seq 50); do grep -q PORT "$T/out" && break; sleep 0.1; done
PORT=$(awk '/PORT/ {print $2}' "$T/out")
[ -n "$PORT" ] || { echo "server did not start"; cat "$T/log"; exit 1; }
B=http://127.0.0.1:$PORT
J="$T/jar"
H=(-H "X-Requested-With: WifiTransfer")
FAIL=0
ok() { echo "ok   $1"; }
bad() { echo "FAIL $1"; FAIL=1; }
# check <name> <expected status> <curl args...>
check() {
	local name=$1 want=$2; shift 2
	local got
	got=$(curl -s -o "$T/body" -w '%{http_code}' "$@")
	if [ "$got" = "$want" ]; then ok "$name ($got)"; else bad "$name: got $got, want $want: $(head -c 200 "$T/body")"; fi
}

# page
check "page loads without PIN" 200 "$B/"
grep -q '<html' "$T/body" || bad "page is HTML"
check "unknown asset" 404 "$B/secret.txt"

# PIN
check "list needs PIN" 401 "$B/api/list?root=easyroms&path="
check "upload needs PIN" 401 "${H[@]}" -T /etc/hostname "$B/api/upload?root=easyroms&dir=&name=x.bin"
check "login without CSRF header" 403 -d pin=1234 "$B/api/login"
check "wrong PIN" 401 "${H[@]}" -d pin=0000 "$B/api/login"
check "right PIN" 200 "${H[@]}" -c "$J" -d pin=1234 "$B/api/login"
check "session" 200 -b "$J" "$B/api/session"
grep -q '"signedIn":true' "$T/body" || bad "session says signed in"
check "fake cookie" 401 -b "wt_session=deadbeef" "$B/api/roots"
check "roots" 200 -b "$J" "$B/api/roots"
grep -q '"EASYROMS"' "$T/body" && grep -q '"Internal storage"' "$T/body" || bad "roots listed"

# upload a file much bigger than the server's 24 MB heap into a new folder: it has to be
# streamed to disk, not buffered
BIG_MB=${BIG_MB:-200}
head -c $((BIG_MB * 1024 * 1024 + 123)) /dev/urandom > "$T/big.bin"
check "upload $BIG_MB MB (server heap 24 MB)" 200 -b "$J" "${H[@]}" -T "$T/big.bin" "$B/api/upload?root=easyroms&dir=snes&name=Big%20Game%20%28USA%29.sfc"
if cmp -s "$T/big.bin" "$T/data/roms/snes/Big Game (USA).sfc"; then ok "uploaded bytes match"; else bad "uploaded bytes differ"; fi
[ -z "$(find "$T/data" -name '*.wtpart')" ] && ok "no temp files left" || bad "temp file left"
check "upload without CSRF header" 403 -b "$J" -T /etc/hostname "$B/api/upload?root=easyroms&dir=&name=a.txt"
check "upload existing file" 409 -b "$J" "${H[@]}" -T "$T/big.bin" "$B/api/upload?root=easyroms&dir=snes&name=Big%20Game%20%28USA%29.sfc"
head -c 1000 /dev/urandom > "$T/small.bin"
check "overwrite" 200 -b "$J" "${H[@]}" -T "$T/small.bin" "$B/api/upload?root=easyroms&dir=snes&name=Big%20Game%20%28USA%29.sfc&overwrite=1"
cmp -s "$T/small.bin" "$T/data/roms/snes/Big Game (USA).sfc" && ok "overwritten bytes match" || bad "overwrite"
check "folder upload" 200 -b "$J" "${H[@]}" -T "$T/small.bin" "$B/api/upload?root=easyroms&dir=psx&name=Game%2Fdisc1.bin"
[ -f "$T/data/roms/psx/Game/disc1.bin" ] && ok "folder upload made subfolders" || bad "folder upload"
check "zero byte upload" 200 -b "$J" "${H[@]}" --data-binary '' -X PUT "$B/api/upload?root=easyroms&dir=&name=empty.txt"
check "bad name" 400 -b "$J" "${H[@]}" -T "$T/small.bin" "$B/api/upload?root=easyroms&dir=&name=a%3Ab.txt"

# confinement
echo secret > "$T/data/secret.txt"
check "list .." 400 -b "$J" "$B/api/list?root=easyroms&path=.."
check "list snes/../.." 400 -b "$J" "$B/api/list?root=easyroms&path=snes/../.."
check "download ../secret" 400 -b "$J" "$B/api/download?root=easyroms&path=../secret.txt"
check "download %2e%2e" 400 -b "$J" "$B/api/download?root=easyroms&path=%2e%2e/secret.txt"
check "download absolute" 404 -b "$J" "$B/api/download?root=easyroms&path=/etc/passwd"
check "upload into .." 400 -b "$J" "${H[@]}" -T "$T/small.bin" "$B/api/upload?root=easyroms&dir=..&name=evil.bin"
check "upload name .." 400 -b "$J" "${H[@]}" -T "$T/small.bin" "$B/api/upload?root=easyroms&dir=&name=..%2Fevil.bin"
[ ! -e "$T/data/evil.bin" ] && ok "nothing written outside" || bad "file written outside root"
ln -s "$T/data" "$T/data/roms/link"
check "symlink out of root" 403 -b "$J" "$B/api/download?root=easyroms&path=link/secret.txt"
rm "$T/data/roms/link"
check "unknown root" 404 -b "$J" "$B/api/list?root=nope&path="
check "raw ../ in URL" 404 -b "$J" --path-as-is "$B/../../etc/passwd"

# list, download, range
check "list" 200 -b "$J" "$B/api/list?root=easyroms&path=snes"
grep -q '"Big Game (USA).sfc"' "$T/body" || bad "list shows the file"
check "download" 200 -b "$J" -o "$T/dl" "$B/api/download?root=easyroms&path=snes/Big%20Game%20%28USA%29.sfc"
curl -s -b "$J" "$B/api/download?root=easyroms&path=snes/Big%20Game%20%28USA%29.sfc" | cmp -s - "$T/small.bin" && ok "downloaded bytes match" || bad "download bytes"
curl -s -b "$J" -r 100-199 "$B/api/download?root=easyroms&path=snes/Big%20Game%20%28USA%29.sfc" | cmp -s - <(tail -c +101 "$T/small.bin" | head -c 100) && ok "range download" || bad "range download"

# folders, rename, delete
check "mkdir" 200 -b "$J" "${H[@]}" -X POST "$B/api/mkdir?root=easyroms&path=gba"
[ -d "$T/data/roms/gba" ] && ok "folder made" || bad "folder made"
check "mkdir again" 409 -b "$J" "${H[@]}" -X POST "$B/api/mkdir?root=easyroms&path=gba"
check "rename" 200 -b "$J" "${H[@]}" -X POST "$B/api/rename?root=easyroms&path=gba&to=GBA%20games"
[ -d "$T/data/roms/GBA games" ] && ok "renamed" || bad "renamed"
check "rename to ../x" 400 -b "$J" "${H[@]}" -X POST "$B/api/rename?root=easyroms&path=GBA%20games&to=..%2Fx"
check "delete root" 403 -b "$J" "${H[@]}" -X POST "$B/api/delete?root=easyroms&path="
check "delete folder with files" 200 -b "$J" "${H[@]}" -X POST "$B/api/delete?root=easyroms&path=psx"
[ ! -e "$T/data/roms/psx" ] && ok "folder deleted" || bad "folder deleted"
check "delete .." 400 -b "$J" "${H[@]}" -X POST "$B/api/delete?root=easyroms&path=.."
[ -f "$T/data/secret.txt" ] && ok "file outside still there" || bad "file outside deleted"
check "GET delete" 405 -b "$J" "${H[@]}" "$B/api/delete?root=easyroms&path=snes"

# lockout: 5 wrong PINs lock the address, even the right PIN waits
for i in 1 2 3 4; do curl -s -o /dev/null "${H[@]}" -d pin=9999 "$B/api/login"; done
check "5th wrong PIN locks" 429 "${H[@]}" -d pin=9999 "$B/api/login"
check "right PIN while locked" 429 "${H[@]}" -d pin=1234 "$B/api/login"
check "existing session still works" 200 -b "$J" "$B/api/roots"

# a cut-off upload leaves nothing behind
printf 'PUT /api/upload?root=easyroms&dir=&name=cut.bin HTTP/1.1\r\nHost: x\r\nCookie: %s\r\nX-Requested-With: WifiTransfer\r\nContent-Length: 1000000\r\n\r\nonly a few bytes' \
	"wt_session=$(awk '/wt_session/ {print $7}' "$J")" > "$T/req"
python3 - "$PORT" "$T/req" <<'PY'
import socket, sys
s = socket.create_connection(("127.0.0.1", int(sys.argv[1])))
s.sendall(open(sys.argv[2], "rb").read())
s.close()
PY
sleep 0.5
[ -z "$(find "$T/data" -name '*.wtpart' -o -name 'cut.bin')" ] && ok "cut-off upload cleaned up" || bad "cut-off upload left a file"

# stop: nothing listens afterwards
exec 3>&-
wait $PID
curl -s -o /dev/null --max-time 2 "$B/" && bad "still listening after stop" || ok "not listening after stop"
echo "--- server log"; cat "$T/log"
[ $FAIL = 0 ] && echo "ALL TESTS PASSED" || echo "SOME TESTS FAILED"
exit $FAIL
