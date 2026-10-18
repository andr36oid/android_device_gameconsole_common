package setup

import (
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Progress is what the setup screen (FirstStart's preparing page) shows while the first
// setup runs. It reads system properties: an app may read sys.andr36oid.*, and property
// writes are cheap. Values stay under Android's 92 character limit.
//
//	sys.andr36oid.setup_stage  the step, a short code (apps, controls, cores:<n>:<total>,
//	                           storage, folders, appsettings, library, games, cheats)
//	sys.andr36oid.setup_app    the app being installed, by file name
//	sys.andr36oid.setup_pct    0-100 of the planned work, by bytes
//	sys.andr36oid.setup_eta    seconds left, -1 while unknown
//	sys.andr36oid.setup_stats  "card=<MB/s> rd=<MB/s> wrs=<MB/s> wr=<MB> apps=<done>/<all>
//	                           t=<s> free=<MB>": card is the speed measured once at the
//	                           start, rd and wrs what the card reads and writes right now
//	                           (all of it, also pm's and the system's), wr what we wrote
//
// The work is planned in bytes (APKs to install, archives to unpack, files to copy): the
// card's speed decides how long all of it takes, on a slow card as on a fast one.
type Progress struct {
	s       *Setup
	Enabled bool

	mu        sync.Mutex
	planned   map[string]int64 // bytes by part (apps, cores, cheats)
	done      map[string]int64
	part      string // the part Begin/Advance/Finish count for
	started   time.Time
	working   time.Duration // time spent on planned work, for the rate
	itemStart time.Time
	written   int64
	appsAll   int
	appsDone  int
	cardMBs   float64
	lastPub   time.Time
	// the card's sector counters at the last sample, for the live read and write speed
	lastIO  time.Time
	lastRd  int64
	lastWr  int64
	rdMBs   float64
	wrMBs   float64
	ticking bool
}

// blockSectors reads the sectors read and written (fields 3 and 7, 512 bytes each) from a
// block device's stat file.
func blockSectors(path string) (rd, wr int64, ok bool) {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0, 0, false
	}
	f := strings.Fields(string(b))
	if len(f) < 7 {
		return 0, 0, false
	}
	rd, err1 := strconv.ParseInt(f[2], 10, 64)
	wr, err2 := strconv.ParseInt(f[6], 10, 64)
	return rd, wr, err1 == nil && err2 == nil
}

// sampleIOLocked updates the live read and write speed of the card since the last sample.
func (p *Progress) sampleIOLocked(now time.Time) {
	if p.s.BlockStat == "" {
		return
	}
	rd, wr, ok := blockSectors(p.s.BlockStat)
	if !ok {
		return
	}
	if !p.lastIO.IsZero() {
		if d := now.Sub(p.lastIO).Seconds(); d >= 0.5 {
			p.rdMBs = float64(rd-p.lastRd) * 512 / 1e6 / d
			p.wrMBs = float64(wr-p.lastWr) * 512 / 1e6 / d
		} else {
			return
		}
	}
	p.lastIO, p.lastRd, p.lastWr = now, rd, wr
}

// Tick publishes once a second while the setup runs, so the screen's live numbers move
// during a long install too. Stopped by Stop.
func (p *Progress) Tick() {
	p.mu.Lock()
	if !p.Enabled || p.ticking {
		p.mu.Unlock()
		return
	}
	p.ticking = true
	p.mu.Unlock()
	go func() {
		for {
			time.Sleep(time.Second)
			p.mu.Lock()
			on := p.ticking
			p.mu.Unlock()
			if !on {
				return
			}
			p.publish(true)
		}
	}()
}

// Stop ends Tick.
func (p *Progress) Stop() {
	p.mu.Lock()
	p.ticking = false
	p.mu.Unlock()
}

func NewProgress(s *Setup) *Progress {
	return &Progress{s: s, planned: map[string]int64{}, done: map[string]int64{}}
}

func (p *Progress) set(name, value string) {
	if !p.Enabled || p.s.Props == nil {
		return
	}
	_ = p.s.Props.Set(name, value)
}

// Stage is the step code for the screen.
func (p *Progress) Stage(code string) { p.set("sys.andr36oid.setup_stage", code) }

// MainDone tells the screen that every start step is done, so only the app page's installs
// can still be running: it ticks off the other steps then.
func (p *Progress) MainDone() { p.set("sys.andr36oid.setup_main_done", "1") }

// App is the app being installed, "" when none.
func (p *Progress) App(file string) {
	file = strings.TrimSuffix(file, ".apk")
	p.set("sys.andr36oid.setup_app", file)
}

// Plan says a part has this many bytes of work left, replacing the estimate made for it
// before (the start estimates every part, each step then knows its own exactly), and for
// the apps part how many apps are left to install. The work counted after this goes to
// part.
func (p *Progress) Plan(part string, left int64, apps int) {
	p.mu.Lock()
	if p.started.IsZero() {
		p.started = p.s.Now()
	}
	p.planned[part] = p.done[part] + left
	p.part = part
	if part == "apps" {
		p.appsAll = p.appsDone + apps
	}
	p.mu.Unlock()
	p.publish(true)
}

// Done says a part's work is over: what is left of its plan no longer counts.
func (p *Progress) Done(part string) {
	p.mu.Lock()
	p.planned[part] = p.done[part]
	p.mu.Unlock()
	p.publish(true)
}

// Begin marks the start of a piece of planned work.
func (p *Progress) Begin() {
	p.mu.Lock()
	p.itemStart = p.s.Now()
	p.mu.Unlock()
}

// Finish counts n more bytes as done and ends the piece of work (also when it failed: it
// won't be done in this run).
func (p *Progress) Finish(n int64, app bool) {
	p.mu.Lock()
	if !p.itemStart.IsZero() {
		p.working += p.s.Now().Sub(p.itemStart)
		p.itemStart = time.Time{}
	}
	p.addLocked(n)
	if app {
		p.appsDone++
	}
	p.mu.Unlock()
	p.publish(false)
}

// Advance counts n bytes of the running piece of work as done (archives report as they
// are read), with the time so far counted for the rate.
func (p *Progress) Advance(n int64) {
	p.mu.Lock()
	now := p.s.Now()
	if !p.itemStart.IsZero() {
		p.working += now.Sub(p.itemStart)
		p.itemStart = now
	}
	p.addLocked(n)
	p.mu.Unlock()
	p.publish(false)
}

func (p *Progress) addLocked(n int64) {
	p.done[p.part] += n
	if p.done[p.part] > p.planned[p.part] {
		p.planned[p.part] = p.done[p.part]
	}
}

func (p *Progress) totalsLocked() (total, done int64) {
	for k, v := range p.planned {
		total += v
		done += p.done[k]
	}
	return total, done
}

// Wrote counts bytes written to the card, for the stats.
func (p *Progress) Wrote(n int64) {
	p.mu.Lock()
	p.written += n
	p.mu.Unlock()
}

// CardSpeed records the card's measured read speed.
func (p *Progress) CardSpeed(mbs float64) {
	p.mu.Lock()
	p.cardMBs = mbs
	p.mu.Unlock()
	p.publish(true)
}

func (p *Progress) cardSpeed() float64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.cardMBs
}

// ETA is the seconds left for the planned work, -1 while there isn't enough to go by.
func (p *Progress) ETA() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.etaLocked()
}

func (p *Progress) etaLocked() int {
	total, done := p.totalsLocked()
	left := total - done
	if left <= 0 {
		return 0
	}
	if done <= 0 || p.working < 3*time.Second {
		// no measured rate yet: from the card's read speed, if known, with room for
		// what Android does on top (an install is much more than reading the APK)
		if p.cardMBs > 0 {
			return int(float64(left)/(p.cardMBs*1e6)*4) + 1
		}
		return -1
	}
	rate := float64(done) / p.working.Seconds()
	return int(float64(left)/rate) + 1
}

func (p *Progress) publish(force bool) {
	if !p.Enabled {
		return
	}
	p.mu.Lock()
	now := p.s.Now()
	if !force && now.Sub(p.lastPub) < time.Second {
		p.mu.Unlock()
		return
	}
	p.lastPub = now
	pct := 0
	if total, done := p.totalsLocked(); total > 0 {
		pct = int(done * 100 / total)
	}
	eta := p.etaLocked()
	elapsed := 0
	if !p.started.IsZero() {
		elapsed = int(now.Sub(p.started).Seconds())
	}
	free, _ := p.s.freeKB(p.s.Media)
	p.sampleIOLocked(now)
	stats := fmt.Sprintf("card=%.1f rd=%.1f wrs=%.1f wr=%d apps=%d/%d t=%d free=%d",
		p.cardMBs, p.rdMBs, p.wrMBs, p.written>>20, p.appsDone, p.appsAll, elapsed, free>>10)
	p.mu.Unlock()
	p.set("sys.andr36oid.setup_pct", fmt.Sprint(pct))
	p.set("sys.andr36oid.setup_eta", fmt.Sprint(eta))
	p.set("sys.andr36oid.setup_stats", stats)
}

// MeasureCard reads up to limit bytes of the given files in order and returns MB/s
// (10^6 bytes), 0 if there was too little to read for a useful number. The AUTOAPK files
// were never read since the card was flashed, so this is the card, not the page cache.
func MeasureCard(files []string, limit int64, now func() time.Time) float64 {
	buf := make([]byte, 1<<20)
	var n int64
	start := now()
	for _, f := range files {
		if n >= limit {
			break
		}
		fh, err := os.Open(f)
		if err != nil {
			continue
		}
		for n < limit {
			k, err := fh.Read(buf)
			n += int64(k)
			if err == io.EOF || err != nil {
				break
			}
		}
		fh.Close()
	}
	d := now().Sub(start).Seconds()
	if n < 4<<20 || d <= 0 {
		return 0
	}
	return float64(n) / 1e6 / d
}
