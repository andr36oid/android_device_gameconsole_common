package emmc

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Event is one line of progress, JSON, for the Settings wizard (it reads the
// progress file twice a second) and the install log.
//
//	state "start": an operation begins (op, dev, from/to sectors, total bytes)
//	state "run":   bytes done so far, speed (at most every half second)
//	state "ok":    done, with its checksum where there is one
//	state "fail":  stopped, log says why; nothing more happens after it
//	no state:      a log line
type Event struct {
	Time  string `json:"time"`
	Step  string `json:"step"`
	Op    string `json:"op,omitempty"`
	Dev   string `json:"dev,omitempty"`
	From  int64  `json:"from,omitempty"`
	To    int64  `json:"to,omitempty"`
	Done  int64  `json:"done,omitempty"`
	Total int64  `json:"total,omitempty"`
	Speed int64  `json:"speed,omitempty"`
	State string `json:"state,omitempty"`
	SHA   string `json:"sha256,omitempty"`
	Log   string `json:"log,omitempty"`
}

// Progress writes events to the progress file and any extra writers (the
// install log next to the backup).
type Progress struct {
	mu   sync.Mutex
	step string
	out  []io.Writer
	now  func() time.Time
}

// pastRuns is how many earlier runs' progress files are kept in past/.
const pastRuns = 20

// NewProgress starts a fresh progress file at path ("" = none). The last run's
// file goes to past/ next to it first, so it can't be read as this run's.
func NewProgress(path, step string) (*Progress, error) {
	p := &Progress{step: step, now: time.Now}
	if path != "" {
		archiveProgress(path)
		f, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0644)
		if err != nil {
			return nil, err
		}
		p.out = append(p.out, f)
	}
	return p, nil
}

// AddLog appends every event to path as well.
func (p *Progress) AddLog(path string) error {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		return err
	}
	p.mu.Lock()
	p.out = append(p.out, f)
	p.mu.Unlock()
	return nil
}

// AddWriter sends progress lines to recovery's pipe as well.
func (p *Progress) AddWriter(w io.Writer) {
	p.mu.Lock()
	p.out = append(p.out, w)
	p.mu.Unlock()
}

func (p *Progress) emit(e Event) {
	e.Time = p.now().UTC().Format("2006-01-02T15:04:05.000Z")
	e.Step = p.step
	b, _ := json.Marshal(e)
	b = append(b, '\n')
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, w := range p.out {
		w.Write(b)
		if f, ok := w.(*os.File); ok {
			f.Sync()
		}
	}
}

// Logf writes a log line.
func (p *Progress) Logf(format string, a ...any) {
	p.emit(Event{Log: fmt.Sprintf(format, a...)})
}

// Op is one running operation.
type Op struct {
	p     *Progress
	e     Event
	start time.Time
	last  time.Time
}

// Start begins an operation over sectors from..to (to excluded) of dev,
// total bytes long.
func (p *Progress) Start(op, dev string, from, to, total int64) *Op {
	o := &Op{p: p, e: Event{Op: op, Dev: dev, From: from, To: to, Total: total}, start: p.now()}
	e := o.e
	e.State = "start"
	p.emit(e)
	return o
}

// Tick reports bytes done.
func (o *Op) Tick(done int64) {
	t := o.p.now()
	if t.Sub(o.last) < 500*time.Millisecond && done < o.e.Total {
		return
	}
	o.last = t
	e := o.e
	e.State = "run"
	e.Done = done
	if s := t.Sub(o.start).Seconds(); s > 0 {
		e.Speed = int64(float64(done) / s)
	}
	o.p.emit(e)
}

// OK ends the operation.
func (o *Op) OK(sha, format string, a ...any) {
	e := o.e
	e.State = "ok"
	e.Done = o.e.Total
	e.SHA = sha
	e.Log = fmt.Sprintf(format, a...)
	if s := o.p.now().Sub(o.start).Seconds(); s > 0 {
		e.Speed = int64(float64(e.Done) / s)
	}
	o.p.emit(e)
}

// Fail ends the operation with err and returns it.
func (o *Op) Fail(err error) error {
	e := o.e
	e.State = "fail"
	e.Log = err.Error()
	o.p.emit(e)
	return err
}

// archiveProgress moves the last run's progress file to past/<time>-<step>.jsonl,
// keeping the newest pastRuns of them. Errors only mean less history.
func archiveProgress(path string) {
	fi, err := os.Stat(path)
	if err != nil || fi.Size() == 0 {
		return
	}
	step := "run"
	if f, err := os.Open(path); err == nil {
		var e Event
		if json.NewDecoder(f).Decode(&e) == nil && e.Step != "" {
			step = e.Step
		}
		f.Close()
	}
	dir := filepath.Join(filepath.Dir(path), "past")
	if os.MkdirAll(dir, 0770) != nil {
		return
	}
	name := fi.ModTime().UTC().Format("20060102-150405.000") + "-" + step + ".jsonl"
	if os.Rename(path, filepath.Join(dir, name)) != nil {
		return
	}
	old, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	// names start with the time: sorted oldest first
	for i := 0; i < len(old)-pastRuns; i++ {
		os.Remove(filepath.Join(dir, old[i].Name()))
	}
}
