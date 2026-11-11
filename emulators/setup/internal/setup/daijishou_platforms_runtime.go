package setup

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

const djPlatformRows = `SELECT COALESCE(group_concat(r, char(10)), '') FROM (SELECT id || ':' || hex(unique_id) || ':' || is_visible || ':' || hex(items_sync_tree_uri_list) AS r FROM PlatformEntity ORDER BY id);`
const djPlayerRows = `SELECT COALESCE(group_concat(r, char(10)), '') FROM (SELECT id || ':' || hex(unique_id) AS r FROM PlayerEntity ORDER BY id);`

type djPlatformState struct {
	UUID          string
	Device, Inode uint64
	Known         map[string]int
	Hidden        map[string]bool
}

type djPlatformPending struct {
	State         djPlatformState
	Before, After string
}

type djPlatformRow struct {
	id         int
	uid, trees string
	visible    bool
}
type djPlatformSpec struct {
	line    seedLine
	players []seedLine
	art     string
}

func (s *Setup) djPlatformStatePath() string { return s.state("daijishou-platforms.json") }
func djSaveJSON(path string, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return fsx.WriteFileAtomic(path, append(data, '\n'))
}

func djReadPlatformRows(text string) (map[string]djPlatformRow, error) {
	out := map[string]djPlatformRow{}
	for _, line := range splitLines(text) {
		f := strings.Split(line, ":")
		if len(f) != 4 {
			return nil, fmt.Errorf("invalid platform snapshot")
		}
		id, err := strconv.Atoi(f[0])
		if err != nil || id <= 0 {
			return nil, fmt.Errorf("invalid platform id")
		}
		uid, err := hex.DecodeString(f[1])
		if err != nil {
			return nil, err
		}
		trees, err := hex.DecodeString(f[3])
		if err != nil {
			return nil, err
		}
		if _, exists := out[string(uid)]; exists {
			return nil, fmt.Errorf("duplicate platform unique_id")
		}
		out[string(uid)] = djPlatformRow{id, string(uid), string(trees), f[2] != "0"}
	}
	return out, nil
}

func (s *Setup) djPlatformSpecs() []djPlatformSpec {
	var out []djPlatformSpec
	available := map[string]bool{}
	checked := map[string]bool{}
	for _, line := range s.djSeedLines() {
		switch line.kind {
		case "platform":
			out = append(out, djPlatformSpec{line: line})
		case "player":
			if len(out) == 0 || out[len(out)-1].line.n != line.n {
				continue
			}
			if !checked[line.arg] {
				checked[line.arg] = true
				available[line.arg] = s.djHas(line.arg)
			}
			if available[line.arg] {
				out[len(out)-1].players = append(out[len(out)-1].players, line)
			}
		case "art":
			if len(out) > 0 && out[len(out)-1].line.n == line.n {
				out[len(out)-1].art = line.arg
			}
		}
	}
	return out
}

func djOurPlatform(row djPlatformRow, spec djPlatformSpec, uuid string) bool {
	var trees []string
	if json.Unmarshal([]byte(row.trees), &trees) != nil || len(trees) == 0 {
		return false
	}
	allowed := map[string]bool{}
	for _, folder := range strings.Split(spec.line.arg, ",") {
		allowed[djTreeURI(uuid, folder)] = true
	}
	for _, tree := range trees {
		if !allowed[tree] {
			return false
		}
	}
	return true
}

func djDBIdentity(path string) (uint64, uint64, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return 0, 0, err
	}
	if !info.Mode().IsRegular() {
		return 0, 0, fmt.Errorf("library is not a regular file")
	}
	st := info.Sys().(*syscall.Stat_t)
	return uint64(st.Dev), uint64(st.Ino), nil
}

// ReconcilePlatforms is serialized by the setup worker. Missing niche systems
// are added once games exist; empty auto-managed systems are hidden, retaining
// every game, favorite, player choice and history row. A user-deleted platform,
// foreign sync folder or replaced/cleared library is not recreated/rewritten.
func (s *Setup) ReconcilePlatforms() {
	if err := s.djReconcilePlatforms(); err != nil {
		s.Log("reconciling Daijishou systems failed: %v", err)
		// A watcher client can leave its request with a different worker, so
		// process exit status alone cannot report a later failure back to it.
		_ = fsx.Touch(s.state("daijishou-platforms.retry"))
	} else {
		_ = os.Remove(s.state("daijishou-platforms.retry"))
	}
}

func (s *Setup) djReconcilePlatforms() error {
	session := s.Props.Get("sys.andr36oid.subsystem.session")
	if session == "running" || session == "cleanup_failed" {
		return nil
	}
	if s.noroms || !sys.IsFile(s.djSeeded()) || !sys.IsFile(s.djDB()) {
		return nil
	}
	root := s.easyroms
	if root == "" {
		root = s.easyromsMounted()
	}
	if root == "" {
		return nil
	}
	uuid := baseOf(root)
	seeded, err := os.ReadFile(s.djSeeded())
	if err != nil {
		return err
	}
	if !uuidOK.MatchString(uuid) || strings.TrimSpace(string(seeded)) != uuid {
		return nil
	}
	for _, line := range s.djSeedLines() {
		if line.kind == "daijishou" && s.installedVC(djPkg) != line.n {
			return nil
		}
	}
	rootInfo, err := os.Stat(root)
	if err != nil {
		return err
	}
	sameRoot := func() bool { info, e := os.Stat(root); return e == nil && os.SameFile(rootInfo, info) }
	dev, ino, err := djDBIdentity(s.djDB())
	if err != nil {
		return err
	}
	tmp, err := os.MkdirTemp(s.State, "daijishou-platforms.")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	db := filepath.Join(tmp, "Daijishou.db")
	if err = copyPlain(s.djDB(), db); err != nil {
		return err
	}
	if sys.Exists(s.djDB() + "-wal") {
		if err = copyPlain(s.djDB()+"-wal", db+"-wal"); err != nil {
			return err
		}
	}
	if check, e := s.sqlQuery(db, "PRAGMA quick_check;"); e != nil || check != "ok" {
		return fmt.Errorf("library snapshot integrity: %s %v", check, e)
	}
	before, err := s.sqlQuery(db, djPlatformRows)
	if err != nil {
		return err
	}
	rows, err := djReadPlatformRows(before)
	if err != nil {
		return err
	}
	// An empty replacement library is a user reset, not a request to reseed it.
	if len(rows) == 0 {
		return nil
	}
	statePath := s.djPlatformStatePath()
	pendingPath := statePath + ".pending"
	var state djPlatformState
	data, err := os.ReadFile(statePath)
	if err == nil {
		if err = json.Unmarshal(data, &state); err != nil {
			return err
		}
		if state.UUID != uuid || state.Device != dev || state.Inode != ino {
			return nil
		}
	} else if errors.Is(err, os.ErrNotExist) {
		state = djPlatformState{UUID: uuid, Device: dev, Inode: ino}
	} else {
		return err
	}
	if state.Known == nil {
		state.Known = map[string]int{}
	}
	if state.Hidden == nil {
		state.Hidden = map[string]bool{}
	}
	// Recover the state/SQLite publication boundary after a power loss. The SQL
	// transaction either applied in full or not at all; do not guess across edits.
	if data, err = os.ReadFile(pendingPath); err == nil {
		var pending djPlatformPending
		if err = json.Unmarshal(data, &pending); err != nil {
			return err
		}
		if pending.State.UUID != uuid || pending.State.Device != dev || pending.State.Inode != ino {
			return nil
		}
		switch before {
		case pending.After:
			state = pending.State
			if err = djSaveJSON(statePath, state); err != nil {
				return err
			}
		case pending.Before:
		default:
			return fmt.Errorf("pending system policy differs from current library; leaving it unchanged")
		}
		if err = os.Remove(pendingPath); err != nil {
			return err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	specs := s.djPlatformSpecs()
	if len(state.Known) == 0 {
		for _, spec := range specs {
			uid := djPlatformID(spec.line)
			if row, ok := rows[uid]; ok && djOurPlatform(row, spec, uuid) {
				state.Known[uid] = row.id
			}
		}
		if len(state.Known) == 0 {
			return nil
		}
	}
	games, err := s.djGameFolders(root)
	if err != nil {
		return err
	}
	if !sameRoot() {
		return fmt.Errorf("ROM volume changed during scan")
	}
	playersText, err := s.sqlQuery(db, djPlayerRows)
	if err != nil {
		return err
	}
	players := map[string]int{}
	nextPlayer, nextPlatform := 1, 1
	for _, line := range splitLines(playersText) {
		f := strings.Split(line, ":")
		if len(f) != 2 {
			return fmt.Errorf("invalid player snapshot")
		}
		id, e := strconv.Atoi(f[0])
		if e != nil {
			return e
		}
		uid, e := hex.DecodeString(f[1])
		if e != nil {
			return e
		}
		if players[string(uid)] == 0 {
			players[string(uid)] = id
		}
		if id >= nextPlayer {
			nextPlayer = id + 1
		}
	}
	for _, row := range rows {
		if row.id >= nextPlatform {
			nextPlatform = row.id + 1
		}
	}
	var changes strings.Builder
	var art []djArtItem
	revealed := false
	for _, spec := range specs {
		uid := djPlatformID(spec.line)
		row, exists := rows[uid]
		known := state.Known[uid]
		// A removed/replaced known ID means the user deleted/imported that system.
		if known != 0 && (!exists || row.id != known) {
			continue
		}
		wanted := djWantedPlatform(spec.line, games)
		if exists {
			if !djOurPlatform(row, spec, uuid) {
				continue
			}
			if !djMainstream[uid] {
				if !wanted && row.visible {
					fmt.Fprintf(&changes, "UPDATE PlatformEntity SET is_visible=0 WHERE id=%d;\n", row.id)
					state.Hidden[uid] = true
				} else if wanted && !row.visible && state.Hidden[uid] {
					fmt.Fprintf(&changes, "UPDATE PlatformEntity SET is_visible=1 WHERE id=%d;\n", row.id)
					delete(state.Hidden, uid)
					revealed = true
				} else if row.visible {
					delete(state.Hidden, uid)
				}
			}
			state.Known[uid] = row.id
			continue
		}
		if !wanted || len(spec.players) == 0 {
			continue
		}
		var uris []string
		for _, folder := range strings.Split(spec.line.arg, ",") {
			if sys.IsDir(filepath.Join(root, folder)) {
				uris = append(uris, djTreeURI(uuid, folder))
			}
		}
		if len(uris) == 0 {
			continue
		}
		var pids []string
		for _, player := range spec.players {
			playerUID := djPlatformID(player)
			id := players[playerUID]
			if id == 0 {
				id = nextPlayer
				nextPlayer++
				players[playerUID] = id
				fmt.Fprintf(&changes, "INSERT INTO PlayerEntity (id, unique_id, player, name, description, am_start_arguments, kill_package_processes, kill_package_processes_warning, accepted_filename_regex, extra) VALUES (%d,%s);\n", id, player.vals)
			}
			pids = append(pids, strconv.Itoa(id))
		}
		trees, _ := json.Marshal(uris)
		fmt.Fprintf(&changes, "INSERT INTO PlatformEntity (id, default_player_id, player_id_list, items_sync_tree_uri_list, unique_id, name, shortname, description, is_visible, scrape_preview_media, wallpaper_path, thumbnail_path, color_theme, color_on_theme, accept_filename_regex, scraper_source_list, box_art_aspect_ratio_id, use_custom_box_art_aspect_ratio, custom_box_art_aspect_ratio, screen_aspect_ratio_id, use_custom_screen_aspect_ratio, custom_screen_aspect_ratio, retro_achievements_console_id_list, extra) VALUES (%d,%s,%s,%s,%s);\n", nextPlatform, pids[0], djQuote("["+strings.Join(pids, ",")+"]"), djQuote(string(trees)), spec.line.vals)
		if spec.art != "" && sys.IsFile(s.djArt()+"/"+spec.art) {
			fmt.Fprintf(&changes, "UPDATE PlatformEntity SET wallpaper_path=%s, thumbnail_path=%s WHERE id=%d;\n", djQuote(fmt.Sprintf("%s/files/platform_wallpapers/%d.png", s.djData(), nextPlatform)), djQuote(fmt.Sprintf("%s/files/platform_thumbnails/%d.png", s.djData(), nextPlatform)), nextPlatform)
			art = append(art, djArtItem{nextPlatform, spec.art})
		}
		state.Known[uid] = nextPlatform
		nextPlatform++
		revealed = true
	}
	if changes.Len() == 0 {
		return djSaveJSON(statePath, state)
	}
	// Check both ID/visibility/folder and player-ID snapshots in the live SQL
	// transaction; concurrent user edits cannot turn our IDs into unrelated rows.
	guard := strings.TrimSuffix(djPlatformRows, ";")
	playerGuard := strings.TrimSuffix(djPlayerRows, ";")
	sql := fmt.Sprintf(".timeout 5000\nBEGIN IMMEDIATE;\nCREATE TEMP TABLE platform_guard (ok INTEGER CHECK(ok=1));\nINSERT INTO platform_guard SELECT CASE WHEN (%s)=%s AND (%s)=%s THEN 1 ELSE 0 END;\n%sCOMMIT;\n", guard, djQuote(before), playerGuard, djQuote(playersText), changes.String())
	file := tmp + "/platforms.sql"
	if err = os.WriteFile(file, []byte(sql), 0600); err != nil {
		return err
	}
	if err = s.sqlRun(db, file); err != nil {
		return err
	}
	after, err := s.sqlQuery(db, djPlatformRows)
	if err != nil {
		return err
	}
	if !sameRoot() {
		return fmt.Errorf("ROM volume disappeared before library update")
	}
	owner, err := fsx.OwnerOf(s.djData())
	if err != nil {
		return err
	}
	top := s.djRunning() && s.djOnTop()
	if _, err = s.R.Run("am", "force-stop", djPkg); err != nil {
		return err
	}
	defer func() {
		_, _ = s.R.Run("am", "force-stop", djPkg)
		for _, suffix := range []string{"", "-wal", "-shm", "-journal"} {
			if sys.Exists(s.djDB() + suffix) {
				_ = fsx.SetOwnerMode(s.djDB()+suffix, owner, 0660)
			}
		}
		s.restorecon(true, dirOf(s.djDB()))
		if top {
			s.goHome()
		}
	}()
	d, i, e := djDBIdentity(s.djDB())
	if e != nil || d != dev || i != ino || !sameRoot() {
		return fmt.Errorf("library or ROM volume replaced during update")
	}
	if err = djSaveJSON(pendingPath, djPlatformPending{state, before, after}); err != nil {
		return err
	}
	if err = s.sqlRun(s.djDB(), file); err != nil {
		_ = os.Remove(pendingPath)
		return err
	}
	if err = djSaveJSON(statePath, state); err != nil {
		return err
	}
	if err = os.Remove(pendingPath); err != nil {
		return err
	}
	s.djCopyArt(art, owner)
	if revealed && s.Props.Get("persist.andr36oid.daijishou_autosync") != "0" && sys.IsFile(s.djPrefs()) {
		if djAskSync(s.djPrefs(), "New game systems are ready. Press OK to look for their games now.") {
			_ = fsx.SetOwnerMode(s.djPrefs(), owner, 0660)
			s.restorecon(false, s.djPrefs())
		}
	}
	s.Log("updated Daijishou's visible game systems")
	return nil
}

// Anchor newly seeded libraries before a user can remove one of their default
// systems. An app-data reset or imported replacement gets a different identity.
func (s *Setup) djRecordSeedPlatforms(uuid, text string) error {
	rows, err := djReadPlatformRows(text)
	if err != nil {
		return err
	}
	dev, ino, err := djDBIdentity(s.djDB())
	if err != nil {
		return err
	}
	state := djPlatformState{UUID: uuid, Device: dev, Inode: ino, Known: map[string]int{}, Hidden: map[string]bool{}}
	for uid, row := range rows {
		state.Known[uid] = row.id
	}
	if err = djSaveJSON(s.djPlatformStatePath(), state); err != nil {
		return err
	}
	_ = os.Remove(s.djPlatformStatePath() + ".pending")
	return nil
}
