package setup

import (
	"crypto/md5"
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

type djPort struct{ name, uri, tree, md5 string }

func (s *Setup) djPortMasterPath() string { return s.djData() + "/files/gameconsole-ports/PM" }
func (s *Setup) djPortMasterURI() string {
	return (&url.URL{Scheme: "file", Path: s.djPortMasterPath()}).String()
}

// Daijishou checks File.exists for {file.path} before launching a file-type game.
// A private, empty marker supplies an existing path whose basename is PM, which
// LaunchPortActivity.resolveSpec passes through as port PM. No launcher script
// or bridge change is needed, and Ports Sync cannot mistake it for a real port.
func (s *Setup) djPortMaster() error {
	path := s.djPortMasterPath()
	if sys.IsFile(path) {
		return nil
	}
	owner, err := fsx.OwnerOf(s.djData())
	if err != nil {
		return err
	}
	for _, dir := range []string{s.djData() + "/files", dirOf(path)} {
		if !sys.IsDir(dir) {
			if err := fsx.MkdirAs(dir, owner, 0o771); err != nil {
				return err
			}
		}
	}
	if err := fsx.WriteFileAtomic(path, nil); err != nil {
		return err
	}
	if err := fsx.SetOwnerMode(path, owner, 0o660); err != nil {
		return err
	}
	s.restorecon(true, dirOf(path))
	return nil
}

// ReconcilePorts runs every boot, independently of daijishou.done/seeded. For an
// on-demand refresh as root, call: /system/bin/gameconsole-setup reconcile-ports
// The command uses the setup worker, so it cannot race the boot's library seed.
func (s *Setup) ReconcilePorts() {
	root := s.easyroms
	if root == "" {
		root = s.easyromsMounted()
	}
	if s.noroms || root == "" || !sys.IsFile(s.djDB()) {
		return
	}
	s.djMigratePorts(root)
	if err := s.djReconcilePorts(root); err != nil {
		s.Log("reconciling Daijishou's ports failed: %v", err)
	}
}

// djMigratePorts adds the Ports platform to a library we seeded before the Ports
// platform existed. The first-boot seed only adds it to brand new libraries, and
// djReconcilePorts never recreates a platform (it respects one the user has deleted),
// so without this an upgraded console would never show the Ports page. It runs once,
// guarded by a marker, and proves its SQL on a copy of the database before touching
// the live one, so a failure can never corrupt the library. It only creates the empty
// platform; the reconcile that follows fills it with the ports and PortMaster.
func (s *Setup) djMigratePorts(root string) {
	marker := s.state("daijishou.ports-migrated")
	if sys.Exists(marker) || !sys.Exists(s.djSeeded()) || !sys.IsFile(s.djDB()) {
		return
	}
	uuid := baseOf(root)
	if uuid == "" || !uuidOK.MatchString(uuid) || !sys.IsDir(root+"/ports") {
		return
	}
	// The Ports platform and its player, taken verbatim from the seed.
	var pvals, playerVals, artFile, pnum string
	for _, l := range s.djSeedLines() {
		if l.kind == "platform" && strings.HasPrefix(l.vals, "'ports',") {
			pvals, pnum = l.vals, l.n
			break
		}
	}
	if pnum == "" {
		return
	}
	for _, l := range s.djSeedLines() {
		if l.n != pnum {
			continue
		}
		if l.kind == "player" && playerVals == "" && s.djHas(l.arg) {
			playerVals = l.vals
		}
		if l.kind == "art" && artFile == "" {
			artFile = l.arg
		}
	}
	if playerVals == "" {
		return // the subsystem app is not installed: nothing to launch a port with
	}

	tmp, err := os.MkdirTemp(s.State, "daijishou-migrate.")
	if err != nil {
		s.Log("adding Daijishou's Ports platform failed: %v", err)
		return
	}
	defer os.RemoveAll(tmp)
	db := tmp + "/Daijishou.db"
	if err := copyPlain(s.djDB(), db); err != nil {
		s.Log("adding Daijishou's Ports platform failed: %v", err)
		return
	}
	if sys.Exists(s.djDB() + "-wal") {
		if err := copyPlain(s.djDB()+"-wal", db+"-wal"); err != nil {
			s.Log("adding Daijishou's Ports platform failed: %v", err)
			return
		}
	}
	// Already there (a fresh install, or a user library that kept it): nothing to
	// add, but set the marker so we stop checking every boot.
	if has, err := s.sqlQuery(db, "SELECT count(*) FROM PlatformEntity WHERE unique_id = 'ports';"); err != nil {
		return
	} else if has != "0" {
		_ = os.WriteFile(marker, nil, 0o600)
		return
	}
	nextPlat, err := s.sqlQuery(db, "SELECT COALESCE(MAX(id), 0) + 1 FROM PlatformEntity;")
	if err != nil {
		return
	}
	nextPlayer, err := s.sqlQuery(db, "SELECT COALESCE(MAX(id), 0) + 1 FROM PlayerEntity;")
	if err != nil {
		return
	}
	plat, err := strconv.Atoi(nextPlat)
	if err != nil {
		return
	}
	player, err := strconv.Atoi(nextPlayer)
	if err != nil {
		return
	}
	tree := `"` + djTreeURI(uuid, "ports") + `"`
	var b strings.Builder
	b.WriteString(".timeout 5000\nBEGIN IMMEDIATE;\n")
	fmt.Fprintf(&b, "INSERT INTO PlayerEntity (id, unique_id, player, name, description, am_start_arguments, kill_package_processes, kill_package_processes_warning, accepted_filename_regex, extra) VALUES (%d, %s);\n", player, playerVals)
	fmt.Fprintf(&b, "INSERT INTO PlatformEntity (id, default_player_id, player_id_list, items_sync_tree_uri_list, unique_id, name, shortname, description, is_visible, scrape_preview_media, wallpaper_path, thumbnail_path, color_theme, color_on_theme, accept_filename_regex, scraper_source_list, box_art_aspect_ratio_id, use_custom_box_art_aspect_ratio, custom_box_art_aspect_ratio, screen_aspect_ratio_id, use_custom_screen_aspect_ratio, custom_screen_aspect_ratio, retro_achievements_console_id_list, extra) VALUES (%d, %d, '[%d]', '[%s]', %s);\n", plat, player, player, tree, pvals)
	if artFile != "" && sys.IsFile(s.djArt()+"/"+artFile) {
		data := s.djData()
		fmt.Fprintf(&b, "UPDATE PlatformEntity SET wallpaper_path = '%s/files/platform_wallpapers/%d.png', thumbnail_path = '%s/files/platform_thumbnails/%d.png' WHERE id = %d;\n", data, plat, data, plat, plat)
	}
	b.WriteString("COMMIT;\n")
	file := tmp + "/migrate.sql"
	if err := os.WriteFile(file, []byte(b.String()), 0o600); err != nil {
		return
	}
	// Prove it applies on the copy and really creates the Ports platform before we
	// touch the live database.
	if err := s.sqlRun(db, file); err != nil {
		s.Log("adding Daijishou's Ports platform failed on a copy, not applied: %v", err)
		return
	}
	if has, err := s.sqlQuery(db, "SELECT count(*) FROM PlatformEntity WHERE unique_id = 'ports';"); err != nil || has != "1" {
		return
	}

	owner, err := fsx.OwnerOf(s.djData())
	if err != nil {
		return
	}
	top := s.djRunning() && s.djOnTop()
	if _, err := s.R.Run("am", "force-stop", djPkg); err != nil {
		return
	}
	defer func() {
		_, _ = s.R.Run("am", "force-stop", djPkg)
		for _, suffix := range []string{"", "-wal", "-shm", "-journal"} {
			if sys.Exists(s.djDB() + suffix) {
				_ = fsx.SetOwnerMode(s.djDB()+suffix, owner, 0o660)
			}
		}
		s.restorecon(true, dirOf(s.djDB()))
		if top {
			s.goHome()
		}
	}()
	if !sys.IsFile(s.djDB()) {
		return
	}
	if err := s.sqlRun(s.djDB(), file); err != nil {
		s.Log("adding Daijishou's Ports platform failed: %v", err)
		return
	}
	if artFile != "" {
		s.djCopyArt([]djArtItem{{plat, artFile}}, owner)
	}
	_ = os.WriteFile(marker, nil, 0o600)
	s.Log("added Daijishou's Ports platform to an upgraded library")
}

func djQuote(v string) string { return "'" + strings.ReplaceAll(v, "'", "''") + "'" }

// Android Uri.encode's default safe characters, for DocumentsContract document IDs.
func djURIEncode(v string) string {
	return strings.NewReplacer("+", "%20", "%21", "!", "%27", "'", "%28", "(", "%29", ")", "%2A", "*").Replace(url.QueryEscape(v))
}

// djPorts scans the actual Ports sync folders on the selected EASYROMS volume.
// Refuse an unavailable/foreign folder or an incomplete scan: it is not evidence
// that games were removed. Ports.json accepts .sh and .SH, excluding dot/resource
// files; hidden directories are excluded as well.
func djPorts(root string, trees []string) ([]djPort, error) {
	var ports []djPort
	uuid := baseOf(root)
	for _, tree := range trees {
		prefix := djTreeURI(uuid, "")
		if !strings.HasPrefix(tree, prefix) {
			return nil, fmt.Errorf("Ports sync folder is not on EASYROMS (%s)", uuid)
		}
		folder, err := url.PathUnescape(strings.TrimPrefix(tree, prefix))
		if err != nil || !filepath.IsLocal(folder) || folder == "." {
			return nil, fmt.Errorf("invalid Ports sync folder %q", tree)
		}
		dir := filepath.Join(root, folder)
		err = filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if strings.HasPrefix(d.Name(), ".") {
				if d.IsDir() {
					return filepath.SkipDir
				}
				return nil
			}
			ext := filepath.Ext(d.Name())
			if !d.Type().IsRegular() || (ext != ".sh" && ext != ".SH") {
				return nil
			}
			f, err := os.Open(path)
			if err != nil {
				return err
			}
			hash := md5.New()
			// Daijishou's LazyMd5 hashes at most the first 4 MiB.
			_, err = io.Copy(hash, io.LimitReader(f, 4<<20))
			f.Close()
			if err != nil {
				return err
			}
			rel, err := filepath.Rel(root, path)
			if err != nil {
				return err
			}
			ports = append(ports, djPort{
				name: strings.TrimSuffix(d.Name(), ext),
				uri:  tree + "/document/" + djURIEncode(uuid+":"+filepath.ToSlash(rel)),
				tree: tree,
				md5:  fmt.Sprintf("%x", hash.Sum(nil)),
			})
			return nil
		})
		if err != nil {
			return nil, err
		}
	}
	if len(trees) == 0 {
		return nil, fmt.Errorf("Ports has no sync folder")
	}
	return ports, nil
}

func (s *Setup) djReconcilePorts(root string) error {
	tmp, err := os.MkdirTemp(s.State, "daijishou-ports.")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	// Like djLibraryCount, read a copy including the WAL. Reading the live WAL
	// database as root could leave a root-owned -shm in Daijishou's app directory.
	db := tmp + "/Daijishou.db"
	if err := copyPlain(s.djDB(), db); err != nil {
		return err
	}
	if sys.Exists(s.djDB() + "-wal") {
		if err := copyPlain(s.djDB()+"-wal", db+"-wal"); err != nil {
			return err
		}
	}
	platform, err := s.sqlQuery(db, "SELECT id || char(10) || items_sync_tree_uri_list FROM PlatformEntity WHERE unique_id = 'ports';")
	if err != nil || platform == "" {
		// No database/platform after the user cleared the library: never recreate it.
		return err
	}
	idText, treeJSON, _ := strings.Cut(platform, "\n")
	id, err := strconv.Atoi(idText)
	if err != nil || id <= 0 {
		return fmt.Errorf("invalid Ports platform id %q", idText)
	}
	var trees []string
	if err := json.Unmarshal([]byte(treeJSON), &trees); err != nil {
		return err
	}
	ports, err := djPorts(root, trees)
	if err != nil {
		return err
	}
	if err := s.djPortMaster(); err != nil {
		return err
	}
	ports = append(ports, djPort{"PortMaster", s.djPortMasterURI(), trees[0], fmt.Sprintf("%x", md5.Sum(nil))})
	gamesQuery := fmt.Sprintf("SELECT id || ':' || hex(uri) FROM PlayableItemEntity WHERE attached_platform_id = %d AND playable_item_type = 1 ORDER BY id;", id)
	before, err := s.sqlQuery(db, gamesQuery)
	if err != nil {
		return err
	}
	// The same transaction is checked on the copy first. A settled boot does not
	// rewrite any rows or interrupt the home screen. Recheck the platform inside
	// the live transaction, in case the user removed/changed it since our read.
	var b strings.Builder
	b.WriteString(".timeout 5000\nBEGIN IMMEDIATE;\n")
	fmt.Fprintf(&b, "CREATE TEMP TABLE ports_platform AS SELECT id FROM PlatformEntity WHERE id = %d AND unique_id = 'ports' AND items_sync_tree_uri_list = %s;\n", id, djQuote(treeJSON))
	b.WriteString("CREATE TEMP TABLE ports_wanted (uri TEXT PRIMARY KEY, name TEXT, tree TEXT, md5 TEXT, keywords TEXT);\n")
	for _, p := range ports {
		keywords, err := json.Marshal([]string{p.name})
		if err != nil {
			return err
		}
		fmt.Fprintf(&b, "INSERT OR IGNORE INTO ports_wanted VALUES (%s, %s, %s, %s, %s);\n", djQuote(p.uri), djQuote(p.name), djQuote(p.tree), djQuote(p.md5), djQuote(string(keywords)))
	}
	b.WriteString("DELETE FROM PlayableItemEntity WHERE attached_platform_id IN (SELECT id FROM ports_platform) AND playable_item_type = 1 AND uri NOT IN (SELECT uri FROM ports_wanted);\n")
	b.WriteString("CREATE TEMP TABLE ports_added AS SELECT w.* FROM ports_wanted w WHERE NOT EXISTS (SELECT 1 FROM PlayableItemEntity g WHERE g.attached_platform_id IN (SELECT id FROM ports_platform) AND g.uri = w.uri);\n")
	// PlayableItemEntity's 1.5.0 defaults, as used by SynchronizationOfPlatform:
	// file type 1, JSON keyword list, visible/searchable, no custom player/history.
	// Set only the new rows' disjoint-set primary id to themselves, as its sync's
	// aggregatePlayableItems does, so counts and favorites work before any Sync.
	fmt.Fprintf(&b, `INSERT INTO PlayableItemEntity (is_singleton_disjoint_set, use_disjoint_set_primary_playable_item, disjoint_set_primary_playable_item_id, playable_item_type, name, do_scrape_name, description, scraper_keyword_list, scrape_scraper_keywords, is_favorite, is_searchable, is_visible, preview_media_path, attached_platform_id, uri, sync_tree_uri, md5, activity_component_string, use_custom_player, custom_player_id, play_count, last_played_timestamp, added_date_timestamp, extra)
SELECT 1, 0, NULL, 1, w.name, 1, NULL, w.keywords, 1, 0, 1, 1, NULL, p.id, w.uri, w.tree, w.md5, NULL, 0, NULL, 0, NULL, %d, '' FROM ports_added w CROSS JOIN ports_platform p;
`, s.Now().UnixMilli())
	b.WriteString("UPDATE PlayableItemEntity SET disjoint_set_primary_playable_item_id = id WHERE attached_platform_id IN (SELECT id FROM ports_platform) AND uri IN (SELECT uri FROM ports_added);\nCOMMIT;\n")
	file := tmp + "/ports.sql"
	if err := os.WriteFile(file, []byte(b.String()), 0o600); err != nil {
		return err
	}
	if err := s.sqlRun(db, file); err != nil {
		return err
	}
	after, err := s.sqlQuery(db, gamesQuery)
	if err != nil || before == after {
		return err
	}
	owner, err := fsx.OwnerOf(s.djData())
	if err != nil {
		return err
	}
	top := s.djRunning() && s.djOnTop()
	if _, err := s.R.Run("am", "force-stop", djPkg); err != nil {
		return err
	}
	defer func() {
		// The home activity may have restarted while we wrote. Restart it with
		// fresh Room data, and leave SQLite-created sidecars owned by the app.
		_, _ = s.R.Run("am", "force-stop", djPkg)
		for _, suffix := range []string{"", "-wal", "-shm", "-journal"} {
			if sys.Exists(s.djDB() + suffix) {
				_ = fsx.SetOwnerMode(s.djDB()+suffix, owner, 0o660)
			}
		}
		s.restorecon(true, dirOf(s.djDB()))
		if top {
			s.goHome()
		}
	}()
	if !sys.IsFile(s.djDB()) {
		return nil
	}
	if err := s.sqlRun(s.djDB(), file); err != nil {
		return err
	}
	s.Log("reconciled Daijishou's ports")
	return nil
}
