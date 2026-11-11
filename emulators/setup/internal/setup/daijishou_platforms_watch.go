package setup

import (
	"context"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"andr36oid/gcsetup/internal/romwatch"
	"andr36oid/gcsetup/internal/sys"
)

// WatchPlatforms dispatches short, serialized rescan jobs after copy/rename/
// delete bursts settle. Normal idle has no ROM scan. Mount polling discovers
// late/new volumes; a watch-limit failure falls back to a one-minute rescan.
func (s *Setup) WatchPlatforms(ctx context.Context, request func()) {
	patterns := map[string]*regexp.Regexp{}
	var folders []string
	for folder, exts := range s.djExts() {
		patterns[folder] = djGamePattern(exts)
		folders = append(folders, folder)
	}
	accept := func(rel string) bool {
		folder, _, _ := strings.Cut(rel, string(os.PathSeparator))
		return djGameName(filepath.Base(rel), patterns[folder])
	}
	poll := time.NewTicker(15 * time.Second)
	defer poll.Stop()
	var watcher *romwatch.Watcher
	var events <-chan struct{}
	var root string
	var rootInfo os.FileInfo
	var timer *time.Timer
	var due <-chan time.Time
	var retry, fallback, policyRetry time.Time
	defer func() {
		if watcher != nil {
			_ = watcher.Close()
		}
		if timer != nil {
			timer.Stop()
		}
	}()
	schedule := func() {
		if timer == nil {
			timer = time.NewTimer(2 * time.Second)
		} else {
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
			timer.Reset(2 * time.Second)
		}
		due = timer.C
	}
	refresh := func() {
		s.evictDisabledPrestage()
		found := s.easyromsMounted()
		info, err := os.Stat(found)
		if found == "" || err != nil {
			found = ""
		}
		replaced := found != root || (found != "" && rootInfo != nil && !os.SameFile(rootInfo, info))
		if replaced {
			if watcher != nil {
				_ = watcher.Close()
			}
			watcher = nil
			events = nil
			root = found
			rootInfo = info
			retry = time.Time{}
			fallback = time.Time{}
		}
		if root == "" {
			return
		}
		// Retry failed queued jobs even if the last file-copy event is over.
		// Successful idle scans never set this marker and do not rescan ROMs.
		if sys.Exists(s.state("daijishou-platforms.retry")) && !time.Now().Before(policyRetry) {
			schedule()
			policyRetry = time.Now().Add(time.Minute)
		}
		if watcher == nil && !time.Now().Before(retry) {
			var err error
			watcher, err = romwatch.New(root, folders, accept)
			if err != nil {
				s.Log("ROM notifications unavailable; using bounded rescans: %v", err)
				retry = time.Now().Add(time.Minute)
			} else {
				events = watcher.Events()
				schedule()
			}
		}
		if watcher == nil && !time.Now().Before(fallback) {
			schedule()
			fallback = time.Now().Add(time.Minute)
		}
	}
	refresh()
	for {
		select {
		case <-ctx.Done():
			return
		case _, ok := <-events:
			schedule()
			if !ok {
				_ = watcher.Close()
				watcher = nil
				events = nil
				retry = time.Time{}
			}
		case <-due:
			due = nil
			if root != "" {
				request()
			}
		case <-poll.C:
			refresh()
		}
	}
}
