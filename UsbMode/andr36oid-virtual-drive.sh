#!/system/bin/sh
# shellcheck shell=ksh
# shellcheck disable=SC2154
# Sourced by the USB owner. Local image work never changes the gadget or vold.

virtual_name() {
	[ "${#1}" -le 87 ] || return 1
	case $1 in ''|*[!a-zA-Z0-9._\ -]*|[!a-zA-Z0-9]*|*..*|*\ |*.) return 1 ;; esac
}

virtual_size() {
	case $1 in ''|0*|*[!0-9]*|?????*) return 1 ;; esac
	awk -v gb="$1" 'BEGIN {exit !(gb>=1 && gb<=1024)}'
}

# Convert KiB to exact decimal bytes even on mksh's 32-bit arithmetic builds.
virtual_bytes() {
	local kb high low
	kb=$1
	if [ "$((1024 * 1073741824))" = 1099511627776 ]; then
		printf '%s\n' "$((kb * 1024))"
	else
		high=$((kb / 1000000 * 1024 + kb % 1000000 * 1024 / 1000000))
		low=$((kb % 1000000 * 1024 % 1000000))
		if [ "$high" = 0 ]; then printf '%s\n' "$low"
		else printf '%s%06d\n' "$high" "$low"; fi
	fi
}

virtual_label() {
	# A common ASCII subset works with all four formatters, including FAT labels.
	case $1 in *[!a-zA-Z0-9_\ -]*) return 1 ;; esac
	case $2 in fat32) case $1 in ''|\ *) return 1 ;; esac; [ "${#1}" -le 11 ] ;; exfat) [ "${#1}" -le 15 ] ;;
	ntfs) [ "${#1}" -le 32 ] ;; ext4) [ "${#1}" -le 16 ] ;; *) return 1 ;; esac
}

virtual_space() {
	local fs free
	fs=$(awk -v id="$easy_mount" '$1==id {for(i=7;i<=NF;i++) if($i=="-") {print $(i+1); exit}}' "$MOUNTS")
	# In this toybox, -P clears -k. Put -k last to retain 1024-byte units.
	free=$(df -Pk "$easy_root" | awk 'NR==2 {printf "%.0f", $4*1024}') || return 1
	case $free in ''|*[!0-9]*) return 1 ;; esac
	printf '%s\t%s\t%s\n' "$free" "$fs" "$easy_ro"
}

virtual_directory() {
	[ -d "$easy_root/isos" ] || mkdir "$easy_root/isos" || return 1
	[ ! -L "$easy_root/isos" ] && [ "$(readlink -f "$easy_root/isos")" = "$easy_root/isos" ] || return 1
	awk -v dir="$easy_root/isos" '$5==dir || index($5,dir "/")==1 {found=1}
	    END {exit found}' "$MOUNTS"
}

virtual_mbr() {
	# The binary sector layout uses awk; allocation sizes use exact integers.
	LC_ALL=C awk -v bytes="$1" -v fs="$2" 'BEGIN {
	    type=fs=="fat32"?12:fs=="ext4"?131:7;
	    for(i=0;i<512;i++) b[i]=0;
	    b[447]=32; b[448]=33; b[450]=type;
	    b[451]=254; b[452]=255; b[453]=255;
	    start=2048; count=bytes/512-start;
	    for(i=0;i<4;i++) {b[454+i]=start%256; start=int(start/256);
	        b[458+i]=count%256; count=int(count/256)}
	    b[510]=85; b[511]=170;
	    for(i=0;i<512;i++) printf "%c", b[i];
	}'
}

virtual_probe() {
	local fs
	# Only the single, bounded MBR partition layout we can safely service.
	fs=$(od -An -v -tu1 -N512 "$1" | awk -v bytes="$2" '
	    {for(i=1;i<=NF;i++) b[n++]=$i} END {
	    if(n!=512 || b[510]!=85 || b[511]!=170 || b[446]!=0) exit 1;
	    for(i=462;i<510;i++) if(b[i]!=0) exit 1;
	    start=b[454]+256*b[455]+65536*b[456]+16777216*b[457];
	    count=b[458]+256*b[459]+65536*b[460]+16777216*b[461];
	    if(start!=2048 || count<=0 || (start+count)*512!=bytes) exit 1;
	    if(b[450]==12) print "fat32"; else if(b[450]==7) print "windows";
	    else if(b[450]==131) print "ext4"; else exit 1;
	}') || return 1
	case $fs in
	fat32) [ "$(dd if="$1" bs=1 skip=1048658 count=8 2>/dev/null)" = 'FAT32   ' ] || return 1 ;;
	windows)
		case $(dd if="$1" bs=1 skip=1048579 count=8 2>/dev/null) in
		'EXFAT   ') fs=exfat ;; 'NTFS    ') fs=ntfs ;; *) return 1 ;; esac ;;
	ext4) [ "$(od -An -tx1 -j1049656 -N2 "$1" | tr -d ' \n')" = 53ef ] || return 1 ;;
	*) return 1 ;;
	esac
	printf '%s\n' "$fs"
}

virtual_progress() {
	setprop "$P.vstage" "$1"
	setprop "$P.vpercent" "$2"
}

virtual_detail() {
	local detail
	detail=$(tail -n 1 "$STATE/virtual.log" 2>/dev/null | LC_ALL=C tr -cd '[:print:]' | cut -c 1-90)
	setprop "$P.vdetail" "${detail:-$1}"
}

virtual_fail() {
	log_step "virtual drive failed: $1"
	setprop "$P.verror" "$1"
	virtual_detail "$1 failed"
	return 1
}

virtual_lease() {
	[ "$(prop request)" = "$v_request" ] || return 1
	[ "$(awk '{print $22}' "/proc/$v_owner/stat" 2>/dev/null)" = "$v_owner_start" ] || return 1
	image_root && [ "$easy_ro" = 0 ] || return 1
	[ "$easy_disk:$easy_cid:$easy_mount:$easy_root" = "$v_source" ] || return 1
	virtual_directory || return 1
	[ -z "${v_stamp:-}" ] || [ "$(image_info "$v_name")" = "$v_stamp" ] || return 1
	[ -z "${v_inode:-}" ] || [ "$(stat -c '%d:%i' "$v_path" 2>/dev/null)" = "$v_inode" ]
}

virtual_run() {
	local rc helper_start
	virtual_lease || return 1
	log_step "virtual drive: $1"
	rm -f "$STATE/virtual/run"
	helper_start=$(awk '{print $22}' "/proc/$$/stat") || return 1
	(
		# A helper killed before journaling the child must not leave a writer.
		while [ ! -f "$STATE/virtual/run" ]; do
			[ "$(awk '{print $22}' "/proc/$$/stat" 2>/dev/null)" = "$helper_start" ] || exit 1
			sleep 0.2
		done
		[ "$(awk '{print $22}' "/proc/$$/stat" 2>/dev/null)" = "$helper_start" ] || exit 1
		exec "$@"
	) >>"$STATE/virtual.log" 2>&1 &
	v_child=$!
	printf '%s %s\n' "$v_child" "$(awk '{print $22}' "/proc/$v_child/stat")" >"$STATE/virtual/child"
	if ! fsync "$STATE/virtual/child" "$STATE/virtual"; then
		kill "$v_child" 2>/dev/null || true
		wait "$v_child" 2>/dev/null || true
		v_child=
		return 1
	fi
	: >"$STATE/virtual/run"
	while kill -0 "$v_child" 2>/dev/null; do
		if ! virtual_lease; then
			virtual_stop_child || return 1
			wait "$v_child" 2>/dev/null || true
			v_child=
			return 1
		fi
		sleep 0.2
	done
	if wait "$v_child"; then rc=0; else rc=$?; fi
	v_child=
	rm -f "$STATE/virtual/child" "$STATE/virtual/run"
	virtual_lease || return 1
	if [ "$rc" != 0 ]; then
		setprop "$P.verror" "$(prop vstage)"
		virtual_detail "$1 failed (exit $rc)"
	fi
	return "$rc"
}

virtual_stop_child() {
	local pid start ticks child_state
	[ -f "$STATE/virtual/child" ] || return 0
	if [ "$(cat "$STATE/virtual/boot")" != "$(cat "$BOOT_ID")" ]; then
		rm -f "$STATE/virtual/child" "$STATE/virtual/run"
		return 0
	fi
	read -r pid start <"$STATE/virtual/child"
	case $pid:$start in *[!0-9:]*|:*) return 1 ;; esac
	case $pid in 0|1) return 1 ;; esac
	[ -n "$start" ] || return 1
	ticks=0
	while [ "$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null)" = "$start" ]; do
		child_state=$(awk '{print $3}' "/proc/$pid/stat" 2>/dev/null) || break
		[ "$child_state" != Z ] || break
		if [ "$ticks" -lt 5 ]; then kill "$pid" 2>/dev/null || true
		else kill -9 "$pid" 2>/dev/null || true; fi
		ticks=$((ticks + 1))
		sleep 0.2
	done
	rm -f "$STATE/virtual/child" "$STATE/virtual/run"
}

virtual_detach() {
	local loop associations
	if [ ! -f "$STATE/virtual/loop" ]; then
		associations=$(losetup -j "$v_path" 2>>"$STATE/virtual.log") || return 1
		[ -z "$associations" ]
		return $?
	fi
	loop=$(cat "$STATE/virtual/loop")
	case $loop in /dev/block/loop[0-9]*|/dev/loop[0-9]*) ;; *) return 1 ;; esac
	# Never detach a loop which has since been given to a different file.
	associations=$(losetup -j "$v_path" 2>>"$STATE/virtual.log") || return 1
	if [ "$(cat "$STATE/virtual/boot")" != "$(cat "$BOOT_ID")" ]; then
		# Loop numbers from a previous boot no longer belong to this operation.
		[ -z "$associations" ] || return 1
		rm -f "$STATE/virtual/loop"
		return 0
	fi
	if printf '%s\n' "$associations" | awk -F: -v loop="$loop" '$1==loop {found=1} END {exit !found}'; then
		losetup -d "$loop" >>"$STATE/virtual.log" 2>&1 || return 1
		associations=$(losetup -j "$v_path" 2>>"$STATE/virtual.log") || return 1
		if printf '%s\n' "$associations" | awk -F: -v loop="$loop" '$1==loop {found=1} END {exit !found}'; then return 1; fi
	fi
	rm -f "$STATE/virtual/loop"
}

virtual_recover() {
	local saved mode inode same_boot token
	[ -d "$STATE/virtual" ] || return 0
	image_root && [ "$easy_ro" = 0 ] && virtual_directory || return 1
	saved=$(cat "$STATE/virtual/cid")
	[ "$saved" = "$easy_cid" ] || return 1
	virtual_stop_child || return 1
	v_name=$(cat "$STATE/virtual/name") mode=$(cat "$STATE/virtual/mode")
	image_name "$v_name" || return 1
	same_boot=0
	[ "$(cat "$STATE/virtual/boot")" != "$(cat "$BOOT_ID")" ] || same_boot=1
	case $mode in create) v_path=$easy_root/isos/.$v_name.creating ;;
	repair) v_path=$easy_root/isos/$v_name ;; *) return 1 ;; esac
	if [ "$mode:$same_boot" = repair:0 ]; then
		# A reboot closed the old loop; repair never owns the image itself.
		rm -rf "$STATE/virtual"
		fsync "$STATE"
		return $?
	fi
	if [ -e "$v_path" ] || [ -L "$v_path" ]; then
		[ -f "$v_path" ] && [ ! -L "$v_path" ] && [ "$(readlink -f "$v_path")" = "$v_path" ] || return 1
		inode=$(cat "$STATE/virtual/inode" 2>/dev/null) || inode=
		if [ "$same_boot" = 0 ] && [ "$(stat -c %s "$v_path")" != 0 ]; then
			# FAT/exFAT inode numbers can change after remount. The creation token
			# lives in the unpartitioned gap and survives every formatter.
			token=$(cat "$STATE/virtual/token") || return 1
			[ -n "$token" ] && [ "$(dd if="$v_path" bs=1 skip=512 count="${#token}" 2>/dev/null)" = "$token" ] || return 1
		elif [ -n "$inode" ] && [ "$same_boot" = 1 ]; then
			[ "$(stat -c '%d:%i' "$v_path")" = "$inode" ] || return 1
		else
			# An interrupted exclusive create may predate the inode journal.
			[ "$mode" = create ] && [ "$(stat -c %s "$v_path")" = 0 ] || return 1
		fi
		virtual_detach || return 1
		if [ "$mode" = create ]; then rm -f "$v_path" || return 1; fi
		if [ "$mode" = repair ]; then fsync "$v_path" || return 1; fi
	else
		[ ! -f "$STATE/virtual/loop" ] || return 1
	fi
	rm -rf "$STATE/virtual"
	fsync "$easy_root/isos" "$STATE"
}

virtual_abort() {
	if [ -n "${v_child:-}" ]; then
		virtual_stop_child || return 1
		wait "$v_child" 2>/dev/null || true
		v_child=
	fi
	virtual_recover
}

virtual_journal() {
	# Publish a complete intent before touching the image; never source a journal.
	mkdir -m 0700 "$STATE/virtual.tmp" || return 1
	printf '%s\n' "$easy_cid" >"$STATE/virtual.tmp/cid"
	printf '%s\n' "$v_name" >"$STATE/virtual.tmp/name"
	printf '%s\n' "$v_request" >"$STATE/virtual.tmp/mode"
	cat "$BOOT_ID" >"$STATE/virtual.tmp/boot" || return 1
	printf 'andr36oid:%s:%s' "$(cat "$BOOT_ID")" "$$" >"$STATE/virtual.tmp/token"
	fsync "$STATE/virtual.tmp/cid" "$STATE/virtual.tmp/name" "$STATE/virtual.tmp/mode" "$STATE/virtual.tmp/boot" "$STATE/virtual.tmp/token" "$STATE/virtual.tmp" || return 1
	mv "$STATE/virtual.tmp" "$STATE/virtual" && fsync "$STATE"
}

virtual_loop() {
	v_loop=$(losetup -f 2>>"$STATE/virtual.log") || { virtual_fail loop; return 1; }
	case $v_loop in /dev/block/loop[0-9]*|/dev/loop[0-9]*) ;; *) return 1 ;; esac
	printf '%s\n' "$v_loop" >"$STATE/virtual/loop"
	fsync "$STATE/virtual/loop" "$STATE/virtual" || return 1
	virtual_run losetup -o 1048576 --sizelimit "$v_partition" "$v_loop" "$v_path"
}

virtual_create() {
	local gb label fs free part chunk next space kib
	v_name=$(prop vname) gb=$(prop vsize) fs=$(prop vfs) label=$(prop vlabel)
	virtual_name "$v_name" || { virtual_fail name; return 1; }
	v_name=$v_name.img
	virtual_size "$gb" || { virtual_fail size; return 1; }
	virtual_label "$label" "$fs" || { virtual_fail label; return 1; }
	kib=$((gb * 1048576))
	v_bytes=$(virtual_bytes "$kib")
	v_partition=$(virtual_bytes "$((kib - 1024))")
	# The three fields are validated numbers and a mountinfo filesystem word.
	space=$(virtual_space) || return 1
	# shellcheck disable=SC2086
	set -- $space
	[ "$#" = 3 ] || return 1
	free=$1 v_hostfs=$2
	awk -v b="$v_bytes" -v free="$free" 'BEGIN {exit !(b<=free-1073741824)}' || { virtual_fail space; return 1; }
	case $v_hostfs in vfat|msdos)
		awk -v b="$v_bytes" 'BEGIN {exit !(b<=4294967295)}' || { virtual_fail fat_limit; return 1; } ;;
	esac
	if [ -e "$easy_root/isos/$v_name" ] || [ -L "$easy_root/isos/$v_name" ]; then virtual_fail exists; return 1; fi
	v_path=$easy_root/isos/.$v_name.creating
	if [ -e "$v_path" ] || [ -L "$v_path" ]; then virtual_fail exists; return 1; fi
	case $fs in fat32) part=newfs_msdos ;; exfat) part=mkfs.exfat ;; ntfs) part=mkfs.ntfs ;; ext4) part=mke2fs ;; esac
	command -v "$part" >/dev/null || { virtual_fail format; return 1; }
	virtual_journal || return 1
	(set -C; : >"$v_path") || return 1
	v_inode=$(stat -c '%d:%i' "$v_path") || return 1
	printf '%s\n' "$v_inode" >"$STATE/virtual/inode"
	fsync "$STATE/virtual/inode" "$STATE/virtual" "$easy_root/isos" || return 1
	dd if="$STATE/virtual/token" of="$v_path" bs=512 seek=1 count=1 conv=notrunc >>"$STATE/virtual.log" 2>&1 || return 1
	fsync "$v_path" || return 1
	virtual_progress allocate 0
	case $v_hostfs in
	ext4|f2fs|xfs|btrfs)
		if ! virtual_run fallocate -l "$v_bytes" "$v_path"; then
			virtual_lease || return 1
			virtual_run truncate -s "$v_bytes" "$v_path" || return 1
			setprop "$P.verror" ''
			setprop "$P.vdetail" ''
		fi ;;
	*)
		# exFAT has no sparse files and this kernel has no exFAT fallocate.
		# vfat fallocate also zero-fills. Bound each growing truncate for progress.
		next=0
		while [ "$next" -lt "$kib" ]; do
			next=$((next + 65536))
			[ "$next" -le "$kib" ] || next=$kib
			virtual_run truncate -s "$(virtual_bytes "$next")" "$v_path" || return 1
			virtual_progress allocate "$((next / 1024 * 100 / (kib / 1024)))"
		done ;;
	esac
	virtual_progress partition 0
	virtual_mbr "$v_bytes" "$fs" >"$STATE/virtual/mbr" || return 1
	virtual_run dd if="$STATE/virtual/mbr" of="$v_path" bs=512 count=1 conv=notrunc || return 1
	virtual_loop || return 1
	virtual_progress format 0
	case $fs in
	fat32)
		chunk=8
		[ "$gb" -lt 8 ] || chunk=32
		[ "$gb" -lt 16 ] || chunk=64
		virtual_run newfs_msdos -F 32 -c "$chunk" -h 255 -u 63 -o 2048 -L "$label" "$v_loop" || return 1 ;;
	exfat) virtual_run mkfs.exfat -L "$label" "$v_loop" || return 1 ;;
	ntfs) virtual_run mkfs.ntfs -f -p 2048 -L "$label" "$v_loop" || return 1 ;;
	ext4) virtual_run mke2fs -t ext4 -L "$label" "$v_loop" || return 1 ;;
	esac
	virtual_progress flush 0
	virtual_detach || return 1
	virtual_run fsync "$v_path" || return 1
	virtual_lease || return 1
	# -n refuses a filename created while formatting. Check that the move happened.
	if [ -e "$easy_root/isos/$v_name" ] || [ -L "$easy_root/isos/$v_name" ]; then virtual_fail exists; return 1; fi
	mv -nT "$v_path" "$easy_root/isos/$v_name" || return 1
	[ ! -e "$v_path" ] || { virtual_fail exists; return 1; }
	v_inode=
	fsync "$easy_root/isos" || return 1
	rm -rf "$STATE/virtual"
	fsync "$STATE"
}

virtual_existing() {
	local info fs rc associations renamed
	v_name=$(prop image)
	info=$(image_info "$v_name") || { virtual_fail changed; return 1; }
	[ "$info" = "$(prop stamp)" ] || { virtual_fail changed; return 1; }
	v_path=$easy_root/isos/$v_name v_inode=${info%:*} v_stamp=$info
	[ "$(stat -c %h "$v_path")" = 1 ] || { virtual_fail changed; return 1; }
	associations=$(losetup -j "$v_path" 2>>"$STATE/virtual.log") || return 1
	[ -z "$associations" ] || { virtual_fail shared; return 1; }
	if [ "$v_request" = delete ]; then
		virtual_lease && [ "$(image_info "$v_name")" = "$info" ] && rm "$v_path" || return 1
		v_inode=
		fsync "$easy_root/isos"
		return $?
	fi
	if [ "$v_request" = rename ]; then
		renamed=$(prop vname)
		virtual_name "$renamed" || { virtual_fail name; return 1; }
		renamed=$renamed.${v_name##*.}
		if [ -e "$easy_root/isos/$renamed" ] || [ -L "$easy_root/isos/$renamed" ]; then virtual_fail exists; return 1; fi
		if ! virtual_lease || [ "$(image_info "$v_name")" != "$info" ]; then virtual_fail changed; return 1; fi
		mv -nT "$v_path" "$easy_root/isos/$renamed" || return 1
		[ ! -e "$v_path" ] || { virtual_fail exists; return 1; }
		v_inode=
		fsync "$easy_root/isos"
		return $?
	fi
	fs=$(virtual_probe "$v_path" "${info##*:}") || { virtual_fail layout; return 1; }
	v_partition=$(awk -v b="${info##*:}" 'BEGIN {printf "%d",b/1024-1024}')
	v_partition=$(virtual_bytes "$v_partition")
	virtual_journal || return 1
	printf '%s\n' "$v_inode" >"$STATE/virtual/inode"
	fsync "$STATE/virtual/inode" "$STATE/virtual" || return 1
	virtual_loop || return 1
	virtual_progress repair 0
	rc=0
	case $fs in
	fat32) virtual_run fsck_msdos -p "$v_loop" || rc=$? ;;
	exfat) virtual_run fsck.exfat -p "$v_loop" || rc=$? ;;
	ntfs) virtual_run fsck.ntfs "$v_loop" || rc=$? ;;
	ext4) virtual_run e2fsck -p "$v_loop" || rc=$? ;;
	esac
	# e2fsck/exfat use bit 0 for corrected errors. Other tools use 0 only.
	case $fs:$rc in *:0|ext4:1|exfat:1) ;; *) return 1 ;; esac
	virtual_detach && virtual_run fsync "$v_path" || return 1
	rm -rf "$STATE/virtual"
	fsync "$STATE"
}

virtual_request() {
	local rc stage
	v_request=$(prop request) v_owner=$(prop owner) v_inode='' v_child='' v_stamp=''
	: >"$STATE/virtual.log"
	rm -rf "$STATE/virtual.tmp"
	setprop "$P.verror" ''
	setprop "$P.vdetail" ''
	setprop "$P.vresult" working
	virtual_progress validate 0
	setprop "$P.vactive" 1
	status working
	rc=1
	case $v_owner in ''|*[!0-9]*) v_owner_start= ;; *) v_owner_start=$(awk '{print $22}' "/proc/$v_owner/stat" 2>/dev/null) ;; esac
	if [ -e "$STATE/owned" ] || [ -d "$F" ] || [ -e "$STATE/restore" ]; then
		virtual_fail shared || true
	elif ! virtual_recover; then
		virtual_fail recovery || true
	elif [ -n "$v_owner_start" ] && image_root && [ "$easy_ro" = 0 ] && [ "$(prop cid)" = "$easy_cid" ]; then
		v_source=$easy_disk:$easy_cid:$easy_mount:$easy_root
		if virtual_lease; then
			: >"$STATE/virtual.log"
			case $v_request in create) virtual_create && rc=0 ;; repair|delete|rename) virtual_existing && rc=0 ;; esac
		fi
	else
		virtual_fail unavailable || true
	fi
	if [ "$rc" = 0 ]; then
		setprop "$P.verror" ''
		setprop "$P.vdetail" ''
		setprop "$P.vresult" 'done'
	else
		stage=$(prop vstage)
		[ -n "$(prop verror)" ] || virtual_fail "$stage" || true
		virtual_abort || virtual_fail recovery || true
		if [ "$(prop request)" = stop ]; then setprop "$P.vresult" cancelled
		else setprop "$P.vresult" error; fi
	fi
	v_inode=
	setprop "$P.request" stop
	scan_images || true
	status idle
	setprop "$P.vactive" 0
}
