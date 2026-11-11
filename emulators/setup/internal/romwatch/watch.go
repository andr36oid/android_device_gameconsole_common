// Package romwatch coalesces filesystem notifications without polling ROM files
// or reading their contents. The setup worker owns all database changes.
package romwatch

import (
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

type Watcher struct {
	file    *os.File
	fd      int
	dirs    map[int]string
	root    string
	folders map[string]bool
	accept  func(string) bool
	events  chan struct{}
}

func New(root string, folders []string, accept func(string) bool) (*Watcher, error) {
	fd, err := syscall.InotifyInit1(syscall.IN_CLOEXEC | syscall.IN_NONBLOCK)
	if err != nil {
		return nil, err
	}
	w := &Watcher{file: os.NewFile(uintptr(fd), root), fd: fd, dirs: map[int]string{}, root: root, folders: map[string]bool{}, accept: accept, events: make(chan struct{}, 1)}
	if err = w.add(root); err != nil {
		w.Close()
		return nil, err
	}
	for _, folder := range folders {
		if folder == "" || filepath.Base(folder) != folder || strings.HasPrefix(folder, ".") {
			w.Close()
			return nil, errors.New("invalid ROM folder")
		}
		w.folders[folder] = true
		if err = w.addTree(filepath.Join(root, folder)); err != nil {
			w.Close()
			return nil, err
		}
	}
	go w.read()
	return w, nil
}
func (w *Watcher) Events() <-chan struct{} { return w.events }
func (w *Watcher) Close() error            { return w.file.Close() }
func (w *Watcher) add(path string) error {
	mask := syscall.IN_CLOSE_WRITE | syscall.IN_MOVED_TO | syscall.IN_MOVED_FROM | syscall.IN_CREATE | syscall.IN_DELETE | syscall.IN_DELETE_SELF | syscall.IN_MOVE_SELF | syscall.IN_UNMOUNT
	wd, err := syscall.InotifyAddWatch(w.fd, path, uint32(mask))
	if err == nil {
		w.dirs[wd] = path
	}
	return err
}
func (w *Watcher) addTree(root string) error {
	if _, err := os.Lstat(root); errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return filepath.WalkDir(root, func(path string, e os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !e.IsDir() {
			return nil
		}
		if path != root && strings.HasPrefix(e.Name(), ".") {
			return filepath.SkipDir
		}
		return w.add(path)
	})
}
func (w *Watcher) notify() {
	select {
	case w.events <- struct{}{}:
	default:
	}
}
func (w *Watcher) read() {
	defer close(w.events)
	buffer := make([]byte, 64*1024)
	for {
		n, err := w.file.Read(buffer)
		if err != nil {
			return
		}
		for offset := 0; offset+syscall.SizeofInotifyEvent <= n; {
			data := buffer[offset:]
			wd := int(int32(binary.NativeEndian.Uint32(data)))
			mask := binary.NativeEndian.Uint32(data[4:])
			size := int(binary.NativeEndian.Uint32(data[12:]))
			offset += syscall.SizeofInotifyEvent + size
			if offset > n {
				return
			}
			if mask&syscall.IN_Q_OVERFLOW != 0 {
				w.notify()
				return
			} // caller rebuilds and rescans
			if mask&syscall.IN_IGNORED != 0 {
				delete(w.dirs, wd)
				continue
			}
			dir, ok := w.dirs[wd]
			if !ok {
				continue
			}
			name := strings.TrimRight(string(data[syscall.SizeofInotifyEvent:syscall.SizeofInotifyEvent+size]), "\x00")
			path := filepath.Join(dir, name)
			rel, err := filepath.Rel(w.root, path)
			if err != nil {
				continue
			}
			if mask&(syscall.IN_DELETE_SELF|syscall.IN_MOVE_SELF|syscall.IN_UNMOUNT) != 0 {
				w.notify()
				return
			}
			first, _, _ := strings.Cut(rel, string(os.PathSeparator))
			if !w.folders[first] {
				continue
			}
			hidden := false
			for _, part := range strings.Split(rel, string(os.PathSeparator)) {
				if strings.HasPrefix(part, ".") {
					hidden = true
					break
				}
			}
			if hidden {
				continue
			}
			if mask&syscall.IN_ISDIR != 0 {
				if mask&(syscall.IN_CREATE|syscall.IN_MOVED_TO) != 0 {
					if err = w.addTree(path); err != nil {
						w.notify()
						return
					}
				}
				w.notify()
			} else if mask&(syscall.IN_CLOSE_WRITE|syscall.IN_MOVED_TO|syscall.IN_MOVED_FROM|syscall.IN_DELETE) != 0 && w.accept(rel) {
				w.notify()
			}
		}
	}
}
