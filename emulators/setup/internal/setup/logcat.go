package setup

import (
	"encoding/binary"
	"net"
	"os"
	"sync"
	"time"
)

// logcat writes one line to Android's main log with the tag gameconsole-emu, straight to
// logd's socket (what liblog does), instead of starting /system/bin/log per line.
var logdw struct {
	once sync.Once
	conn *net.UnixConn
}

func logcat(msg string) {
	logdw.once.Do(func() {
		c, err := net.DialUnix("unixgram", nil, &net.UnixAddr{Name: "/dev/socket/logdw", Net: "unixgram"})
		if err == nil {
			logdw.conn = c
		}
	})
	if logdw.conn == nil {
		return
	}
	const tag = "gameconsole-emu"
	now := time.Now()
	// android_log_header_t: id (LOG_ID_MAIN 0), tid, realtime sec and nsec; then
	// priority (INFO 4), tag, message, both with their NUL
	hdr := make([]byte, 11)
	hdr[0] = 0
	binary.LittleEndian.PutUint16(hdr[1:], uint16(os.Getpid()))
	binary.LittleEndian.PutUint32(hdr[3:], uint32(now.Unix()))
	binary.LittleEndian.PutUint32(hdr[7:], uint32(now.Nanosecond()))
	pkt := append(hdr, 4)
	pkt = append(pkt, tag...)
	pkt = append(pkt, 0)
	pkt = append(pkt, msg...)
	pkt = append(pkt, 0)
	_, _ = logdw.conn.Write(pkt)
}
