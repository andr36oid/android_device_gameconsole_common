package setup

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

type djLibraryState struct {
	favorite bool
	count    int64
	played   int64 // Daijishou uses Unix milliseconds; ES stores UTC whole seconds.
}

func (a djLibraryState) merge(b djLibraryState) djLibraryState {
	return djLibraryState{a.favorite || b.favorite, max(a.count, b.count), max(a.played, b.played)}
}

func djLibraryTime(text string) int64 {
	for _, layout := range []string{"20060102T150405", "20060102T150405Z", time.RFC3339} {
		if t, err := time.Parse(layout, strings.TrimSpace(text)); err == nil {
			return max(0, t.UnixMilli())
		}
	}
	return 0
}

// Unescape the whole document path before taking its basename: SAF encodes the
// directory separators too. PathUnescape deliberately leaves literal '+' alone.
func djLibraryName(path string) string {
	if strings.Contains(path, "://") {
		u, err := url.Parse(path)
		if err != nil {
			return ""
		}
		path = u.EscapedPath()
	}
	path, err := url.PathUnescape(path)
	if err != nil || path == "" || strings.HasSuffix(path, "/") {
		return ""
	}
	return filepath.Base(path)
}

// Keep byte ranges rather than re-encoding the XML. Unknown scraper fields,
// folder entries, attributes, comments, CDATA and formatting survive unchanged.
type djLibraryElement struct {
	start, openEnd, closeStart, end int
	name, value                     string
}

type djLibraryGame struct {
	djLibraryElement
	fields map[string][]djLibraryElement
}

func (g *djLibraryGame) field(name string) string {
	if fields := g.fields[name]; len(fields) > 0 {
		return fields[0].value
	}
	return ""
}

func (g *djLibraryGame) state() djLibraryState {
	n, _ := strconv.ParseInt(strings.TrimSpace(g.field("playcount")), 10, 64)
	fav := strings.TrimSpace(g.field("favorite"))
	return djLibraryState{fav == "true" || fav == "1", max(0, n), djLibraryTime(g.field("lastplayed"))}
}

type djLibraryList struct {
	path, root, system string
	data               []byte
	missing            bool
	djLibraryElement
	games []*djLibraryGame
}

func djLibraryParse(data []byte) (*djLibraryList, error) {
	l := &djLibraryList{data: data}
	d := xml.NewDecoder(bytes.NewReader(data))
	var stack []*djLibraryElement
	var game *djLibraryGame
	for {
		start := int(d.InputOffset())
		token, err := d.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, err
		}
		switch t := token.(type) {
		case xml.StartElement:
			e := &djLibraryElement{start: start, openEnd: int(d.InputOffset()), name: t.Name.Local}
			if len(stack) == 0 {
				if e.name != "gameList" || l.name != "" {
					return nil, fmt.Errorf("expected one gameList root")
				}
				l.djLibraryElement = *e
				e = &l.djLibraryElement
			} else if len(stack) == 1 && e.name == "game" {
				game = &djLibraryGame{djLibraryElement: *e, fields: map[string][]djLibraryElement{}}
				l.games = append(l.games, game)
				e = &game.djLibraryElement
			}
			stack = append(stack, e)
		case xml.CharData:
			if len(stack) > 0 {
				stack[len(stack)-1].value += string(t)
			} else if strings.TrimSpace(string(t)) != "" {
				return nil, fmt.Errorf("text outside gameList")
			}
		case xml.EndElement:
			e := stack[len(stack)-1]
			e.closeStart, e.end = start, int(d.InputOffset())
			if len(stack) == 3 && game != nil {
				game.fields[e.name] = append(game.fields[e.name], *e)
			}
			if len(stack) == 2 {
				game = nil
			}
			stack = stack[:len(stack)-1]
		}
	}
	if l.name == "" || l.end == 0 {
		return nil, fmt.Errorf("missing gameList root")
	}
	return l, nil
}

type djLibraryEdit struct {
	start, end int
	text       string
}

func (l *djLibraryList) appendXML(e djLibraryElement, text string) djLibraryEdit {
	if e.closeStart == e.openEnd && bytes.HasSuffix(l.data[e.start:e.openEnd], []byte("/>")) {
		return djLibraryEdit{e.openEnd - 2, e.openEnd, ">" + text + "</" + e.name + ">"}
	}
	return djLibraryEdit{e.closeStart, e.closeStart, text}
}

func (l *djLibraryList) render(states map[*djLibraryGame]djLibraryState) []byte {
	var edits []djLibraryEdit
	var newGames strings.Builder
	for _, g := range l.games {
		state, ok := states[g]
		if !ok {
			continue
		}
		old := g.state()
		var add strings.Builder
		if g.end == 0 { // A real ROM and DB item exist, but ES has no metadata entry.
			add.WriteString("<game><path>" + xmlEscape(g.field("path")) + "</path>")
		}
		values := []struct {
			name, text string
			changed    bool
		}{
			{"favorite", strconv.FormatBool(state.favorite), state.favorite != old.favorite},
			{"playcount", strconv.FormatInt(state.count, 10), state.count != old.count},
			{"lastplayed", time.UnixMilli(state.played).UTC().Format("20060102T150405"), state.played/1000 != old.played/1000},
		}
		for _, v := range values {
			if !v.changed {
				continue
			}
			text := "<" + v.name + ">" + v.text + "</" + v.name + ">"
			if fields := g.fields[v.name]; len(fields) > 0 {
				for _, f := range fields {
					edits = append(edits, djLibraryEdit{f.start, f.end, text})
				}
			} else {
				add.WriteString(text)
			}
		}
		if g.end == 0 {
			newGames.WriteString(add.String() + "</game>\n")
		} else if add.Len() > 0 {
			edits = append(edits, l.appendXML(g.djLibraryElement, add.String()))
		}
	}
	if newGames.Len() > 0 {
		edits = append(edits, l.appendXML(l.djLibraryElement, newGames.String()))
	}
	sort.SliceStable(edits, func(i, j int) bool { return edits[i].start < edits[j].start })
	var b bytes.Buffer
	pos := 0
	for _, e := range edits {
		b.Write(l.data[pos:e.start])
		b.WriteString(e.text)
		pos = e.end
	}
	b.Write(l.data[pos:])
	return b.Bytes()
}

func (l *djLibraryList) write(states map[*djLibraryGame]djLibraryState) error {
	next := l.render(states)
	if bytes.Equal(next, l.data) {
		return nil
	}
	owner, err := fsx.OwnerOf(l.path)
	var mode os.FileMode
	if os.IsNotExist(err) {
		owner, err = fsx.OwnerOf(dirOf(l.path))
		mode = 0o664 // Explicitly readable by media_rw despite init's umask 077.
	} else if err == nil {
		mode, err = fsx.ModeOf(l.path)
	}
	if err != nil {
		return err
	}
	// Refuse a changed/deleted gamelist rather than overwriting a concurrent edit.
	cur, err := os.ReadFile(l.path)
	if (err != nil && !os.IsNotExist(err)) || (err == nil && !bytes.Equal(cur, l.data)) ||
		(os.IsNotExist(err) && !l.missing) || (err == nil && l.missing) {
		return fmt.Errorf("gamelist changed since read: %s", l.path)
	}
	f, err := os.CreateTemp(dirOf(l.path), ".gamelist.*")
	if err != nil {
		return err
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	_, err = f.Write(next)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = fsx.SetOwnerMode(tmp, owner, mode)
	}
	if err == nil {
		err = syncRename(tmp, l.path)
	}
	return err
}

var djLibraryID = regexp.MustCompile(`^'((?:[^']|'')*)',`)

// The seed is generated from platforms.txt and official JSON unique_ids. Reuse
// its loader, including aliases (genesis/megadrive, msx/msx2), just like djFolders.
func (s *Setup) djLibraryFolders() map[string]string {
	out := map[string]string{}
	for _, l := range s.djSeedLines() {
		m := djLibraryID.FindStringSubmatch(l.vals)
		if l.kind != "platform" || len(m) != 2 {
			continue
		}
		for _, folder := range strings.Split(l.arg, ",") {
			if filepath.IsLocal(folder) && !strings.Contains(folder, "/") && folder != "." {
				out[folder] = strings.ReplaceAll(m[1], "''", "'")
			}
		}
	}
	return out
}

func (s *Setup) djLibraryRoots() []string {
	var roots []string
	// mount_storage binds these sorted directories as /roms, /roms2, /roms3...
	ents, _ := os.ReadDir(s.MediaRW)
	for _, e := range ents {
		if e.IsDir() && !strings.HasPrefix(e.Name(), ".") {
			roots = append(roots, filepath.Join(s.MediaRW, e.Name()))
		}
	}
	return roots
}

// Resolve a game/image path only onto a mounted games card. Absolute ES paths
// refer to the broker's bind mounts; relative image paths are system-relative.
func djLibraryPath(path, dir string, roots []string) string {
	if strings.Contains(path, "://") {
		u, err := url.Parse(path)
		if err != nil || (u.Scheme != "content" && u.Scheme != "file") {
			return ""
		}
		path = u.EscapedPath()
	}
	path, err := url.PathUnescape(path)
	if err != nil || path == "" {
		return ""
	}
	if _, doc, ok := strings.Cut(path, "/document/"); ok {
		uuid, rel, ok := strings.Cut(doc, ":")
		if !ok || !filepath.IsLocal(rel) {
			return ""
		}
		path = ""
		for _, root := range roots {
			if baseOf(root) == uuid {
				path = filepath.Join(root, rel)
				break
			}
		}
		if path == "" {
			return ""
		}
	}
	for i, root := range roots {
		mount := "/roms"
		if i > 0 {
			mount += strconv.Itoa(i + 1)
		}
		for _, prefix := range []string{mount, "/storage/" + baseOf(root)} {
			if strings.HasPrefix(path, prefix+"/") {
				path = filepath.Join(root, strings.TrimPrefix(path, prefix+"/"))
				break
			}
		}
	}
	if !filepath.IsAbs(path) {
		path = filepath.Join(dir, path)
	}
	path = filepath.Clean(path)
	for _, root := range roots {
		if rel, err := filepath.Rel(root, path); err == nil && filepath.IsLocal(rel) {
			// Do not import art through symlinks pointing outside the shared cards.
			resolved, err := filepath.EvalSymlinks(path)
			if err == nil {
				if rel, err := filepath.Rel(root, resolved); err == nil && filepath.IsLocal(rel) {
					return path
				}
			}
		}
	}
	return ""
}

type djLibraryItem struct {
	id, platform                             int64
	unique, uri, tree, trees, preview, extra string
	djLibraryState
	list       *djLibraryList
	game       *djLibraryGame
	image, art string
	artReady   bool
}

func (s *Setup) djLibraryItems(db string) ([]djLibraryItem, error) {
	text, err := s.sqlQuery(db, `SELECT g.id, g.attached_platform_id, hex(p.unique_id), hex(g.uri), hex(g.sync_tree_uri), hex(p.items_sync_tree_uri_list), g.is_favorite, g.play_count, COALESCE(g.last_played_timestamp, 0), hex(g.preview_media_path), hex(g.extra)
FROM PlayableItemEntity g JOIN PlatformEntity p ON p.id = g.attached_platform_id WHERE g.playable_item_type = 1 ORDER BY g.id;`)
	if err != nil {
		return nil, err
	}
	var items []djLibraryItem
	for _, line := range splitLines(text) {
		f := strings.Split(line, "|")
		if len(f) != 11 {
			return nil, fmt.Errorf("invalid library row %q", line)
		}
		var n [5]int64
		for j, i := range []int{0, 1, 6, 7, 8} {
			n[j], err = strconv.ParseInt(f[i], 10, 64)
			if err != nil {
				return nil, err
			}
		}
		for _, i := range []int{2, 3, 4, 5, 9, 10} {
			b, err := hex.DecodeString(f[i])
			if err != nil {
				return nil, err
			}
			f[i] = string(b)
		}
		items = append(items, djLibraryItem{id: n[0], platform: n[1], unique: f[2], uri: f[3], tree: f[4], trees: f[5], preview: f[9], extra: f[10], djLibraryState: djLibraryState{n[2] != 0, max(0, n[3]), max(0, n[4])}})
	}
	return items, nil
}

func (s *Setup) djLibraryMatch(items []djLibraryItem, roots []string, fields librarySyncFields) ([]djLibraryItem, error) {
	folders := s.djLibraryFolders()
	var lists []*djLibraryList
	for _, root := range roots {
		var systems []string
		for folder := range folders {
			systems = append(systems, folder)
		}
		sort.Strings(systems)
		for _, folder := range systems {
			dir := filepath.Join(root, folder)
			if !sys.IsDir(dir) {
				continue
			}
			path := filepath.Join(dir, "gamelist.xml")
			data, err := os.ReadFile(path)
			missing := os.IsNotExist(err)
			if os.IsNotExist(err) {
				data, err = []byte("<gameList>\n</gameList>\n"), nil
			}
			if err != nil {
				return nil, err
			}
			l, err := djLibraryParse(data)
			if err != nil {
				return nil, fmt.Errorf("%s: %w", path, err)
			}
			l.path, l.root, l.system = path, root, folder
			l.missing = missing
			lists = append(lists, l)
		}
	}
	type ref struct {
		l *djLibraryList
		g *djLibraryGame
	}
	byName := map[string][]ref{}
	for _, l := range lists {
		for _, g := range l.games {
			if name := djLibraryName(g.field("path")); name != "" {
				key := folders[l.system] + "\x00" + name
				byName[key] = append(byName[key], ref{l, g})
			}
		}
	}
	var matched []djLibraryItem
	for _, item := range items {
		name := djLibraryName(item.uri)
		if name == "" {
			continue
		}
		refs := byName[item.unique+"\x00"+name]
		path := djLibraryPath(item.uri, "", roots)
		if len(refs) == 1 {
			item.list, item.game = refs[0].l, refs[0].g
		} else if len(refs) > 1 {
			// Basename collisions are ambiguous unless the URI identifies the ROM.
			for _, r := range refs {
				if path != "" && path == djLibraryPath(r.g.field("path"), dirOf(r.l.path), roots) {
					if item.game != nil {
						item.game = nil
						break
					}
					item.list, item.game = r.l, r.g
				}
			}
		} else if path != "" && sys.IsFile(path) {
			for _, l := range lists {
				rel, err := filepath.Rel(dirOf(l.path), path)
				if err == nil && filepath.IsLocal(rel) && folders[l.system] == item.unique {
					g := &djLibraryGame{fields: map[string][]djLibraryElement{"path": {{value: "./" + filepath.ToSlash(rel)}}}}
					l.games = append(l.games, g)
					item.list, item.game = l, g
					byName[item.unique+"\x00"+name] = []ref{{l, g}}
					break
				}
			}
		}
		if item.game == nil {
			continue
		}
		item.djLibraryState = fields.merge(item.djLibraryState, item.game.state())
		if fields.desc && item.extra == "" {
			item.extra = item.game.field("desc")
		} else {
			item.extra = "" // Existing user metadata is never a write-back source.
		}
		if fields.art && item.preview == "" {
			item.image = djLibraryPath(item.game.field("image"), dirOf(item.list.path), roots)
			item.art = fmt.Sprintf("%s/files/preview_media/%d/", s.djData(), item.id)
			item.artReady = djLibraryOwnArt(item)
			if !sys.IsFile(item.image) || (sys.Exists(strings.TrimSuffix(item.art, "/")) && !item.artReady) {
				item.image, item.art = "", ""
			}
		}
		matched = append(matched, item)
	}
	return matched, nil
}

// SQL always merges against the live values, and rechecks both item and platform
// identity/sync folders inside the transaction. Only existing rows are updated.
func djLibrarySQL(items []djLibraryItem, fields librarySyncFields) string {
	var b strings.Builder
	b.WriteString(".timeout 5000\nBEGIN IMMEDIATE;\n")
	for _, item := range items {
		where := fmt.Sprintf("id = %d AND uri = %s AND sync_tree_uri = %s AND playable_item_type = 1 AND attached_platform_id = %d AND EXISTS (SELECT 1 FROM PlatformEntity WHERE id = %d AND unique_id = %s AND items_sync_tree_uri_list = %s)", item.id, djQuote(item.uri), djQuote(item.tree), item.platform, item.platform, djQuote(item.unique), djQuote(item.trees))
		fav := 0
		if item.favorite {
			fav = 1
		}
		var sets, changes []string
		if fields.favorite {
			sets = append(sets, fmt.Sprintf("is_favorite = MAX(is_favorite, %d)", fav))
			changes = append(changes, fmt.Sprintf("is_favorite < %d", fav))
		}
		if fields.playcount {
			sets = append(sets, fmt.Sprintf("play_count = MAX(play_count, %d)", item.count))
			changes = append(changes, fmt.Sprintf("play_count < %d", item.count))
		}
		if fields.lastplayed {
			sets = append(sets, fmt.Sprintf("last_played_timestamp = CASE WHEN %d > COALESCE(last_played_timestamp, 0) THEN %d ELSE last_played_timestamp END", item.played, item.played))
			changes = append(changes, fmt.Sprintf("COALESCE(last_played_timestamp, 0) < %d", item.played))
		}
		if fields.desc {
			sets = append(sets, fmt.Sprintf("extra = CASE WHEN COALESCE(extra, '') = '' THEN %s ELSE extra END", djQuote(item.extra)))
			changes = append(changes, fmt.Sprintf("(COALESCE(extra, '') = '' AND %s != '')", djQuote(item.extra)))
		}
		if fields.art {
			sets = append(sets, fmt.Sprintf("preview_media_path = CASE WHEN COALESCE(preview_media_path, '') = '' AND %s != '' THEN %s ELSE preview_media_path END", djQuote(item.art), djQuote(item.art)))
			changes = append(changes, fmt.Sprintf("(COALESCE(preview_media_path, '') = '' AND %s != '')", djQuote(item.art)))
		}
		if len(sets) > 0 {
			fmt.Fprintf(&b, "UPDATE PlayableItemEntity SET %s WHERE %s AND (%s);\nSELECT 'changed|' || changes();\n", strings.Join(sets, ",\n"), where, strings.Join(changes, " OR "))
		}
		fmt.Fprintf(&b, "SELECT id, is_favorite, play_count, COALESCE(last_played_timestamp, 0), hex(preview_media_path) FROM PlayableItemEntity WHERE %s;\n", where)
	}
	b.WriteString("COMMIT;\n")
	return b.String()
}

type djLibraryResult struct {
	state   djLibraryState
	preview string
}

// A power loss after publishing art but before COMMIT leaves a complete import.
// Recognize only our own URI-marked directory so the next sync can attach it
// without replacing files or claiming the user's unrelated preview directory.
func djLibraryOwnArt(item djLibraryItem) bool {
	marker, err := os.ReadFile(item.art + ".subsystem-uri")
	if err != nil || string(marker) != item.uri {
		return false
	}
	data, err := os.ReadFile(item.art + "index.json")
	var index map[string]string
	return err == nil && json.Unmarshal(data, &index) == nil && len(index) == 1 &&
		index["boxArtPath"] == "boxart"+filepath.Ext(item.image) && sys.IsFile(item.art+index["boxArtPath"])
}

func (s *Setup) djLibraryApply(db, file string) (map[int64]djLibraryResult, bool, error) {
	out, err := s.R.Run(s.sqlite(), "-bail", db, ".read "+file)
	if err != nil || strings.Contains(out, "Error:") || strings.Contains(out, "Parse error") {
		return nil, false, fmt.Errorf("%v: %s", err, out)
	}
	rows := map[int64]djLibraryResult{}
	changed := false
	for _, line := range splitLines(out) {
		f := strings.Split(line, "|")
		if len(f) == 2 && f[0] == "changed" {
			changed = changed || f[1] != "0"
			continue
		}
		if len(f) != 5 {
			return nil, false, fmt.Errorf("invalid library result %q", line)
		}
		var n [4]int64
		for i := range n {
			n[i], err = strconv.ParseInt(f[i], 10, 64)
			if err != nil {
				return nil, false, err
			}
		}
		preview, err := hex.DecodeString(f[4])
		if err != nil {
			return nil, false, err
		}
		rows[n[0]] = djLibraryResult{djLibraryState{n[1] != 0, max(0, n[2]), max(0, n[3])}, string(preview)}
	}
	return rows, changed, nil
}

// Publish a complete new art directory. Rename cannot overwrite a directory
// containing existing artwork; a pre-existing directory is left alone entirely.
func (s *Setup) djLibraryArt(item djLibraryItem, owner fsx.Owner) error {
	if item.artReady {
		if djLibraryOwnArt(item) {
			return nil
		}
		return fmt.Errorf("preview directory changed for item %d", item.id)
	}
	parent := dirOf(strings.TrimSuffix(item.art, "/"))
	if err := fsx.MkdirAllAs(parent, owner, 0o771); err != nil {
		return err
	}
	stage, err := os.MkdirTemp(parent, ".subsystem-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)
	if err := fsx.SetOwnerMode(stage, owner, 0o771); err != nil {
		return err
	}
	name := "boxart" + filepath.Ext(item.image)
	if err := fsx.CopyAtomic(item.image, stage+"/"+name); err != nil {
		return err
	}
	index, err := json.Marshal(map[string]string{"boxArtPath": name})
	if err != nil {
		return err
	}
	if err := fsx.WriteFileAtomic(stage+"/index.json", index); err != nil {
		return err
	}
	if err := fsx.WriteFileAtomic(stage+"/.subsystem-uri", []byte(item.uri)); err != nil {
		return err
	}
	for _, path := range []string{stage + "/" + name, stage + "/index.json", stage + "/.subsystem-uri"} {
		if err := fsx.SetOwnerMode(path, owner, 0o660); err != nil {
			return err
		}
	}
	if sys.Exists(strings.TrimSuffix(item.art, "/")) {
		return fmt.Errorf("preview directory appeared for item %d", item.id)
	}
	return os.Rename(stage, strings.TrimSuffix(item.art, "/"))
}

// SyncLibrary runs through the same serialized worker as ReconcilePorts, after
// subsystem sessions and at boot. ES is closed at both call sites. Disable the
// whole sync with persist.andr36oid.subsystem_autosync=0.
func (s *Setup) SyncLibrary() {
	fields := s.librarySyncFields()
	if s.Props.Get("persist.andr36oid.subsystem_autosync") == "0" || !sys.IsFile(s.djDB()) {
		return
	}
	// A boot worker can outlive boot: do not touch an active subsystem's gamelist.
	if s.djLibraryESRunning() {
		return
	}
	if fields == (librarySyncFields{}) {
		return
	}
	if err := s.djSyncLibrary(s.djLibraryRoots(), fields); err != nil {
		s.Log("syncing subsystem library state failed: %v", err)
	}
}

func (s *Setup) djLibraryESRunning() bool {
	if s.Props.Get("sys.andr36oid.subsystem.session") != "running" {
		return false
	}
	_, err := s.R.Run("pidof", "emulationstation", "EmulationStation")
	return err == nil
}

func (s *Setup) djSyncLibrary(roots []string, fields librarySyncFields) error {
	if len(roots) == 0 {
		return nil
	}
	tmp, err := os.MkdirTemp(s.State, "daijishou-library.")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	// As in djReconcilePorts, never read the live WAL DB as root: that could
	// create a root-owned -shm next to the app's database.
	db := tmp + "/Daijishou.db"
	if err := copyPlain(s.djDB(), db); err != nil {
		return err
	}
	if sys.Exists(s.djDB() + "-wal") {
		if err := copyPlain(s.djDB()+"-wal", db+"-wal"); err != nil {
			return err
		}
	}
	items, err := s.djLibraryItems(db)
	if err != nil {
		return err
	}
	items, err = s.djLibraryMatch(items, roots, fields)
	if err != nil || len(items) == 0 {
		return err
	}
	file := tmp + "/library.sql"
	if err := os.WriteFile(file, []byte(djLibrarySQL(items, fields)), 0o600); err != nil {
		return err
	}
	rows, changed, err := s.djLibraryApply(db, file)
	if err != nil {
		return err
	}
	if changed {
		owner, err := fsx.OwnerOf(s.djData())
		if err != nil {
			return err
		}
		top := s.djRunning() && s.djOnTop()
		if _, err := s.R.Run("am", "force-stop", djPkg); err != nil {
			return err
		}
		defer func() {
			_, _ = s.R.Run("am", "force-stop", djPkg)
			for _, suffix := range []string{"", "-wal", "-shm", "-journal"} {
				if sys.Exists(s.djDB() + suffix) {
					_ = fsx.SetOwnerMode(s.djDB()+suffix, owner, 0o660)
				}
			}
			s.restorecon(true, dirOf(s.djDB()), s.djData()+"/files/preview_media")
			if top {
				s.goHome()
			}
		}()
		if !sys.IsFile(s.djDB()) {
			return nil
		}
		installed := map[int64]djLibraryItem{}
		// Stage art before the transaction references it; remove unused imports
		// on rollback or if the live identity/preview recheck rejects the row.
		defer func() {
			for id, item := range installed {
				if row, ok := rows[id]; (!ok || row.preview != item.art) && djLibraryOwnArt(item) && fsx.SameContent(item.image, item.art+"boxart"+filepath.Ext(item.image)) {
					_ = os.Remove(item.art + "index.json")
					_ = os.Remove(item.art + "boxart" + filepath.Ext(item.image))
					_ = os.Remove(item.art + ".subsystem-uri")
					_ = os.Remove(strings.TrimSuffix(item.art, "/"))
				}
			}
		}()
		rows = nil
		for i := range items {
			if items[i].art == "" {
				continue
			}
			if err := s.djLibraryArt(items[i], owner); err != nil {
				s.Log("importing ES art for item %d failed: %v", items[i].id, err)
				items[i].art = ""
			} else if !items[i].artReady {
				installed[items[i].id] = items[i]
			}
		}
		if err := os.WriteFile(file, []byte(djLibrarySQL(items, fields)), 0o600); err != nil {
			return err
		}
		rows, _, err = s.djLibraryApply(s.djDB(), file)
		if err != nil {
			return err
		}
		s.Log("synced subsystem library state with Daijishou")
	}
	byList := map[*djLibraryList]map[*djLibraryGame]djLibraryState{}
	for _, item := range items {
		row, ok := rows[item.id]
		if !ok {
			continue
		}
		if byList[item.list] == nil {
			byList[item.list] = map[*djLibraryGame]djLibraryState{}
		}
		states := byList[item.list]
		state, ok := states[item.game]
		if !ok {
			state = item.game.state()
		}
		states[item.game] = fields.merge(state, row.state)
	}
	for l, states := range byList {
		if s.djLibraryESRunning() {
			return nil
		}
		if err := l.write(states); err != nil {
			return err
		}
	}
	return nil
}
