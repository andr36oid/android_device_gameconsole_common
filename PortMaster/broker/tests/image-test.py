from pathlib import Path
import subprocess,struct,hashlib,os,shutil
import tempfile
workspace=tempfile.TemporaryDirectory(prefix='a36-images-')
base=Path(workspace.name)
w=Path(__file__).resolve().parents[3];support=base/'support'
subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror',str(w/'PortMaster/erofs-tools/andr36oid-erofs-support.c'),'-o',str(support)],check=True)
for card,uuid in [('card1','aaaa-1111'),('card2','bbbb-2222')]:
 d=base/card/'subsystem/darkosen';d.mkdir(parents=True,exist_ok=True)
 p=d/'dArk OS.img';b=bytearray(512+131072);b[510:512]=b'\x55\xaa';b[450]=0x83;struct.pack_into('<II',b,454,1,256)
 x=512+65536;b[x+32:x+48]=bytes(range(16));struct.pack_into('<Q',b,x+48,65536);b[x+64:x+72]=b'_BHRfS_M';struct.pack_into('<Q',b,x+72,1);struct.pack_into('<Q',b,x+112,131072);struct.pack_into('<Q',b,x+136,1);p.write_bytes(b)
 info=dict(line.split('=',1) for line in subprocess.check_output([str(support),'probe',str(p)],text=True).splitlines())
 if card=='card1':
  e=d/'dArk OS.erofs.img';b=bytearray(4096);struct.pack_into('<I',b,1024,0xe0f5e1e2);b[1036]=12;struct.pack_into('<I',b,1060,1);b[1072:1088]=bytes(range(16));e.write_bytes(b)
  fields=dict(version='1',job='a'*16,source_name_hex=p.name.encode().hex(),source_id=hashlib.sha256(uuid.encode()+b'\0'+p.name.encode()).hexdigest(),generation=info['generation'],offset=info['offset'],sectors=info['sectors'],source_size=info['size'],card_uuid=uuid,tool='erofs-utils-1.8.10',options='b4096,lz4,no-xattr-filter,preserve-mtime,workers1',output_size='4096',output_uuid=bytes(range(16)).hex())
  Path(str(e)+'.meta').write_text(''.join(k+'='+v+'\n' for k,v in fields.items()))
broker=(w/'PortMaster/broker/andr36oid-subsystem.sh').read_text()
recovery=[]
for name in ['session_active','recover_session','recover_session_locked']:
 start=broker.index(name+'() {');end=broker.index('\n}\n',start)+3;recovery.append(broker[start:end])
script=r'''#!/bin/bash
set -eu
T=TEST_ROOT
STATE=$T/state;ROOT=$T/root;LOG=$T/log;IMAGE=;A36_CONTROL=$T/control;A36_EROFS_SUPPORT=$T/support;A36_STORAGE_LOCK=$T/storage.lock
mkdir -p "$STATE" "$ROOT" "$T/props"
getprop() { cat "$T/props/$1" 2>/dev/null || true; }
setprop() { printf '%s' "$2" > "$T/props/$1"; }
log() { printf '%s\n' "$*" >> "$LOG"; }
. LIBRARY
MUTE_PIDFILE=$STATE/mute
RECOVERY_FUNCTIONS
image_cards() { printf '%s\taaaa-1111\tfake1\trw\n%s\tbbbb-2222\tfake2\trw\n' "$T/card1" "$T/card2"; }
first=$(selection_id aaaa-1111 'dArk OS.img');second=$(selection_id bbbb-2222 'dArk OS.img')
setprop "$SELECT_PROP" "$first"
resolve_selected_image
[ "$SOURCE_ID" = "$first" ] && [ "$EFFECTIVE" = "$T/card1/subsystem/darkosen/dArk OS.erofs.img" ]
prepare_writable_state
[ "$UPPER" = "$STATE/images/$first/$GENERATION/native1/upper" ]
write_catalog
[ "$(wc -l < "$STATE/images.tsv")" = 2 ]
# Selected original ID survives deletion; the pair stays one visible image.
mv "$T/card1/subsystem/darkosen/dArk OS.img" "$T/original.saved"
resolve_selected_image
[ "$FAMILY" = "$first" ] && [ "$SOURCE_ID" = "$first" ] && [ "$EFFECTIVE_FORMAT" = erofs ]
write_catalog;[ "$(wc -l < "$STATE/images.tsv")" = 2 ]
setprop "$SELECT_PROP" "$second";resolve_selected_image
[ "$SOURCE" = "$T/card2/subsystem/darkosen/dArk OS.img" ]
# A missing explicit selection must not silently launch a different image.
setprop "$SELECT_PROP" "$(selection_id missing 'dArk OS.img')"
! resolve_selected_image
[ "$RESOLVE_STATE" = selected_missing ]
mv "$T/original.saved" "$T/card1/subsystem/darkosen/dArk OS.img"
setprop "$SELECT_PROP" "$first"
cp "$T/card1/subsystem/darkosen/dArk OS.erofs.img.meta" "$T/good.meta"
sed 's/^generation=.*/generation=0000000000000000000000000000000000000000000000000000000000000000/' "$T/good.meta" > "$T/card1/subsystem/darkosen/dArk OS.erofs.img.meta"
resolve_selected_image
[ "$EFFECTIVE" = "$SOURCE" ] && [ "$RESOLVE_STATE" = fallback_plain ]
cp "$T/good.meta" "$T/card1/subsystem/darkosen/dArk OS.erofs.img.meta"
# A USB/storage lease blocks every operation before any file changes.
acquire_operation test
! (exec 9>&- 7>&-; exec 6>"$A36_STORAGE_LOCK";flock -n 6 6>&6)
release_operation
# Both retained recovery journals and large byte counts must be handled safely.
mkdir -p "$STATE/session.lock"
! acquire_operation select
! acquire_operation optimize
rmdir "$STATE/session.lock"
A36_USB_STATE=$T/usb;mkdir -p "$A36_USB_STATE";touch "$A36_USB_STATE/storage"
! acquire_operation optimize
rm "$A36_USB_STATE/storage"
bytes_ge 2147483648 553648128
bytes_ge 8589934592 4294967295
! bytes_ge 4294967295 8589934592
(exec 6>"$A36_STORAGE_LOCK";flock -n 6 6>&6)
# A previous-boot session journal cannot hide the image catalog indefinitely.
mkdir "$STATE/session.lock";printf 'old-boot' > "$STATE/session.lock/boot_id"
printf '99999999' > "$STATE/session.lock/pid";printf '1' > "$STATE/session.lock/start_time"
scan_managed_images
[ ! -d "$STATE/session.lock" ] && [ "$(prop image)" = 1 ]
printf 'PASS: paired catalog, native overlay isolation, original removal, second card, missing selection, stale record, shared storage lease\n'
'''.replace('RECOVERY_FUNCTIONS','\n'.join(recovery)).replace('TEST_ROOT',str(base)).replace('LIBRARY',str(w/'PortMaster/broker/andr36oid-images.sh'))
(base/'resolver.sh').write_text(script)
for shell in ['bash','mksh']:
 if shutil.which(shell): subprocess.run([shell,str(base/'resolver.sh')],check=True)
