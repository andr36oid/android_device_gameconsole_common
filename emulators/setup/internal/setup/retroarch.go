package setup

import (
	"bufio"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode/utf8"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

const (
	raPkg = "com.retroarch"
	// RetroArch's folder on the internal storage as apps see it
	raExtPublic  = "/storage/emulated/0/Android/data/" + raPkg + "/files"
	aidExtDataRW = 1078
)

// raData is RetroArch's private folder, raExt its folder on the internal storage where it
// reads retroarch.cfg, raOwn our state inside raData (clearing RetroArch's data or
// reinstalling it starts our seeding over).
func (s *Setup) raData() string { return s.AppData + "/" + raPkg }
func (s *Setup) raExt() string  { return s.Media + "/Android/data/" + raPkg + "/files" }
func (s *Setup) raOwn() string  { return s.raData() + "/.gameconsole" }
func (s *Setup) raCfg() string  { return s.raExt() + "/retroarch.cfg" }
func (s *Setup) raManifest() manifest {
	return manifest{s.raOwn() + "/seeded.sha1"}
}

// extOwner is the owner vold gives an app's folder on the internal storage: the app, and
// the group ext_data_rw.
func extOwner(app fsx.Owner) fsx.Owner { return fsx.Owner{UID: app.UID, GID: aidExtDataRW} }

const extDirMode = 0o770 | os.ModeSetgid

// netplayName is the name the other player sees in RetroArch netplay: the device name
// from Settings > About. While that is still the model name every console has, the device
// and 4 characters of this console's Android ID, e.g. R36S-3F9A.
func (s *Setup) netplayName() string {
	name := s.Settings.Get("global", "device_name")
	if name == "" || name == s.Props.Get("ro.product.model") {
		dev := strings.ToUpper(s.Props.Get("ro.product.device"))
		id := s.Settings.Get("secure", "android_id")
		if utf8.RuneCountInString(id) > 4 {
			id = string([]rune(id)[:4])
		}
		id = strings.ToUpper(id)
		if id == "NULL" {
			id = ""
		}
		if dev == "" {
			dev = "R36S"
		}
		name = dev
		if id != "" {
			name += "-" + id
		}
	}
	// RetroArch keeps 31 characters; quotes and backslashes would break the cfg
	name = strings.NewReplacer(`"`, "", `\`, "").Replace(name)
	if r := []rune(name); len(r) > 31 {
		name = string(r[:31])
	}
	return name
}

// seedOwned copies src to dest unless the user changed dest. What we put there is in the
// manifest, so a newer image can update it.
func (s *Setup) seedOwned(src, dest string) {
	f := baseOf(dest)
	m := s.raManifest()
	if sys.Exists(dest) {
		if fsx.SameContent(dest, src) {
			return
		}
		sum, _ := fsx.SHA1(dest)
		if !m.has(sum, f) {
			s.Log("keeping user provided %s", f)
			return
		}
	}
	if err := fsx.CopyAtomic(src, dest); err != nil {
		s.Log("copying %s failed", f)
		return
	}
	sum, _ := fsx.SHA1(dest)
	_ = m.set(sum, f)
	s.Log("seeded %s", f)
}

// SeedRetroArch: the pad profiles, and on a fresh install the settings from
// Base/retroarch/retroarch.cfg (ArkOS hotkeys, saves next to the game). A retroarch.cfg
// that exists is never touched.
func (s *Setup) SeedRetroArch() {
	data := s.raData()
	if !sys.IsDir(data) {
		return
	}
	owner, err := fsx.OwnerOf(data)
	if err != nil {
		return
	}
	own := s.raOwn()
	auto := data + "/autoconfig/android"
	_ = os.MkdirAll(own, 0o700)
	_ = os.MkdirAll(auto, 0o700)
	s.raManifest().touch()

	// RetroArch looks for pad profiles in autoconfig/android of its data dir: the
	// built-in pad's, ours for external pads and the upstream set. Only when the image
	// brings other profiles than last time (a stamp), so a normal start doesn't hash
	// ~220 files.
	pads, _ := filepath.Glob(s.Base + "/retroarch/autoconfig/*.cfg")
	sort.Strings(pads)
	var all []byte
	for _, p := range pads {
		b, err := os.ReadFile(p)
		if err == nil {
			all = append(all, b...)
		}
	}
	sum := fsx.SHA1Bytes(all)
	if fsx.ReadTrim(own+"/autoconfig.sha1") != sum {
		for _, p := range pads {
			if sys.IsFile(p) {
				s.seedOwned(p, auto+"/"+baseOf(p))
			}
		}
		_ = fsx.ChownR(data+"/autoconfig", owner)
		_ = fsx.ChownR(own, owner)
		s.restorecon(true, data+"/autoconfig", own)
		_ = fsx.WriteLine(own+"/autoconfig.sha1", sum)
	}

	src := s.Base + "/retroarch/retroarch.cfg"
	cfg := s.raCfg()
	if !sys.IsFile(src) || sys.Exists(cfg) || !s.androidData() {
		return
	}
	// RetroArch hasn't run yet: make its folder the way vold does, only for what we
	// create here
	ext := s.raExt()
	for _, dir := range []string{dirOf(ext), ext} {
		if sys.IsDir(dir) {
			continue
		}
		if err := fsx.MkdirAs(dir, extOwner(owner), extDirMode); err != nil {
			s.Log("making %s failed", dir)
			return
		}
	}
	if err := fsx.CopyAtomic(src, cfg); err != nil {
		s.Log("writing %s failed", cfg)
		return
	}
	if err := s.cfgSet(cfg, "netplay_nickname", s.netplayName()); err != nil {
		s.Log("setting the netplay name failed")
	}
	_ = fsx.SetOwnerMode(cfg, extOwner(owner), 0o660)
	s.restorecon(true, dirOf(ext))
	s.fixupAppDir(raExtPublic)
	// this install gets our picture settings too, see SeedLook
	_ = fsx.WriteLine(own+"/look", "new")
	s.Log("seeded retroarch.cfg")
}

// SeedCoreOptions: core option defaults tuned for the Cortex-A35, next to retroarch.cfg.
// RetroArch reads this file for every core that has no options file of its own yet;
// once the user changes an option, RetroArch writes that per-core file. Written when
// missing, updated by a newer image only while unchanged.
func (s *Setup) SeedCoreOptions() {
	const f = "retroarch-core-options.cfg"
	src := s.Base + "/retroarch/" + f
	dest := s.raExt() + "/" + f
	if !sys.IsFile(src) || !sys.IsDir(s.raData()) || !sys.IsDir(s.raExt()) {
		return
	}
	m := s.raManifest()
	m.touch()
	if sys.Exists(dest) {
		if fsx.SameContent(dest, src) {
			return
		}
		sum, _ := fsx.SHA1(dest)
		if !m.has(sum, f) {
			s.Log("keeping user provided %s", f)
			return
		}
	}
	owner, err := fsx.OwnerOf(s.raData())
	if err != nil {
		return
	}
	if err := fsx.CopyAtomic(src, dest); err != nil {
		s.Log("writing %s failed", dest)
		return
	}
	_ = fsx.SetOwnerMode(dest, extOwner(owner), 0o660)
	s.restorecon(false, dest)
	sum, _ := fsx.SHA1(dest)
	_ = m.set(sum, f)
	_ = fsx.Chown(m.path, owner)
	s.Log("seeded %s", f)
}

// SeedCores puts the bundled libretro cores into RetroArch's core folder, where both
// RetroArch and Daijishou's RetroArch players look for them. Cores the user replaced
// (e.g. with RetroArch's online updater) are kept. Each archive is unpacked once per
// version (a stamp with its SHA-1).
func (s *Setup) SeedCores() {
	data := s.raData()
	if !sys.IsDir(data) {
		return
	}
	cores := data + "/cores"
	own := s.raOwn()
	_ = os.MkdirAll(cores, 0o700)
	_ = os.MkdirAll(own, 0o700)
	m := s.raManifest()
	m.touch()

	archives, _ := filepath.Glob(s.Base + "/libretro/cores-*.tar.gz")
	sort.Strings(archives)
	type job struct {
		archive, sum string
		size         int64
	}
	var todo []job
	for _, a := range archives {
		if !sys.IsFile(a) {
			continue
		}
		sum, err := fsx.SHA1(a)
		if err != nil {
			continue
		}
		if fsx.ReadTrim(own+"/"+baseOf(a)+".sha1") == sum {
			continue
		}
		st, _ := os.Stat(a)
		todo = append(todo, job{a, sum, st.Size()})
	}
	var bytes int64
	for _, j := range todo {
		bytes += j.size
	}
	s.Progress.Plan("cores", bytes, 0)
	for i, j := range todo {
		s.Progress.Stage(sprintf("cores:%d:%d", i+1, len(todo)))
		s.Progress.Begin()
		s.unpackCores(j.archive, j.sum, cores, own, m)
		s.Progress.Finish(0, false)
		s.poll()
	}
	s.Progress.Done("cores")

	if owner, err := fsx.OwnerOf(data); err == nil {
		_ = fsx.ChownR(cores, owner)
		_ = fsx.ChownR(own, owner)
	}
	_ = os.Chmod(cores, 0o700)
	_ = os.Chmod(own, 0o700)
	sos, _ := filepath.Glob(cores + "/*.so")
	for _, so := range sos {
		_ = os.Chmod(so, 0o600)
	}
	s.restorecon(true, cores, own)
}

func (s *Setup) unpackCores(archive, sum, cores, own string, m manifest) {
	tmp := own + "/tmp"
	_ = os.RemoveAll(tmp)
	if err := os.MkdirAll(tmp, 0o700); err != nil {
		return
	}
	defer os.RemoveAll(tmp)
	if err := extractTarGz(archive, tmp, s.Progress.Advance); err != nil {
		s.Log("unpacking %s failed", archive)
		return
	}
	sos, _ := filepath.Glob(tmp + "/cores/*.so")
	sort.Strings(sos)
	for _, so := range sos {
		if !sys.IsFile(so) {
			continue
		}
		f := baseOf(so)
		dest := cores + "/" + f
		// only overwrite what we put there ourselves
		if sys.Exists(dest) {
			cur, _ := fsx.SHA1(dest)
			if !m.has(cur, f) {
				s.Log("keeping user provided %s", f)
				continue
			}
		}
		if err := os.Rename(so, dest); err != nil {
			if err := fsx.CopyAtomic(so, dest); err != nil {
				s.Log("copying %s failed", f)
				continue
			}
		}
		if st, err := os.Stat(dest); err == nil {
			s.Progress.Wrote(st.Size())
		}
		h, _ := fsx.SHA1(dest)
		_ = m.set(h, f)
		s.Log("seeded %s", f)
	}
	_ = fsx.WriteLine(own+"/"+baseOf(archive)+".sha1", sum)
}

// coresEstimate is the size of the core archives never unpacked for this RetroArch
// install, for the first plan.
func (s *Setup) coresEstimate() int64 {
	archives, _ := filepath.Glob(s.Base + "/libretro/cores-*.tar.gz")
	var n int64
	for _, a := range archives {
		if sys.Exists(s.raOwn() + "/" + baseOf(a) + ".sha1") {
			continue
		}
		if st, err := os.Stat(a); err == nil {
			n += st.Size()
		}
	}
	return n
}

// SeedGameOptions: on a fresh install (the retroarch.cfg we wrote, see SeedLook), core
// options for single games that need them on this chip, from game-options.txt. RetroArch
// uses config/<core>/<ROM file name>.opt instead of the core's options file for that game
// only. Options missing there get the core's own defaults, not ours, so each file starts
// with our defaults for that core, then the game's lines. A file the user changed or
// removed stays so. Runs before SeedLook, which marks a new retroarch.cfg as seeded.
//
// game-options.txt: blocks of "game <ROM name>" lines, then their "set <key> = <value>"
// lines, under a "core <option prefix> <core name>" line.
func (s *Setup) SeedGameOptions() {
	src := s.Base + "/retroarch/game-options.txt"
	own := s.raOwn()
	if !sys.IsFile(src) || !sys.IsDir(s.raExt()) || !sys.Exists(own+"/look") {
		return
	}
	owner, err := fsx.OwnerOf(s.raData())
	if err != nil {
		return
	}
	m := s.raManifest()
	m.touch()
	// a new retroarch.cfg starts over: files removed before come back
	if fsx.ReadTrim(own+"/look") == "new" {
		_ = m.dropContaining(" games/")
	}
	defaults := s.raExt() + "/retroarch-core-options.cfg"
	if !sys.IsFile(defaults) {
		defaults = s.Base + "/retroarch/retroarch-core-options.cfg"
	}
	b, err := os.ReadFile(src)
	if err != nil {
		return
	}
	var core, prefix, last string
	var names, sets []string
	flush := func() {
		for _, n := range names {
			s.gameOptWrite(core, prefix, n, defaults, sets, owner)
		}
		names, sets = nil, nil
	}
	for _, line := range splitLines(string(b)) {
		switch {
		case strings.HasPrefix(line, "core ") || strings.HasPrefix(line, "game "):
			if last == "opt" {
				flush()
			}
			if rest, ok := strings.CutPrefix(line, "core "); ok {
				names = nil
				prefix, core, _ = strings.Cut(rest, " ")
				if !strings.Contains(rest, " ") {
					core = rest
				}
				last = "core"
			} else {
				names = append(names, strings.TrimPrefix(line, "game "))
				last = "game"
			}
		case strings.HasPrefix(line, "set "):
			sets = append(sets, strings.TrimPrefix(line, "set "))
			last = "opt"
		}
	}
	if last == "opt" {
		flush()
	}
	_ = fsx.Chown(m.path, owner)
}

// gameOptWrite writes config/<core>/<ROM name>.opt: our defaults for the core (keys that
// start with prefix), then the game's own lines, which win.
func (s *Setup) gameOptWrite(core, prefix, name, defaults string, sets []string, owner fsx.Owner) {
	m := s.raManifest()
	key := "games/" + core + "/" + name + ".opt"
	dest := s.raExt() + "/config/" + core + "/" + name + ".opt"
	var text strings.Builder
	text.WriteString("# " + name + ": settings for this game on this console, see Game tips.txt\n")
	if df, err := os.ReadFile(defaults); err == nil {
		for _, l := range splitLines(string(df)) {
			if !strings.HasPrefix(l, prefix) {
				continue
			}
			k, _, _ := strings.Cut(l, " ")
			set := false
			for _, sl := range sets {
				if strings.HasPrefix(sl, k+" ") {
					set = true
					break
				}
			}
			if !set {
				text.WriteString(l + "\n")
			}
		}
	}
	for _, sl := range sets {
		text.WriteString(sl + "\n")
	}
	content := []byte(text.String())
	want := fsx.SHA1Bytes(content)
	if sys.Exists(dest) {
		cur, _ := fsx.SHA1(dest)
		if cur == want {
			return
		}
		if !m.has(cur, key) {
			s.Log("keeping user provided config/%s/%s.opt", core, name)
			return
		}
	} else if m.hasKey(key) {
		// we put it there before, so the user removed it
		return
	}
	for _, dir := range []string{s.raExt() + "/config", s.raExt() + "/config/" + core} {
		if sys.IsDir(dir) {
			continue
		}
		if err := fsx.MkdirAs(dir, extOwner(owner), extDirMode); err != nil {
			s.Log("making %s failed", dir)
			return
		}
		s.restorecon(false, dir)
	}
	if err := fsx.WriteFileAtomic(dest, content); err != nil {
		s.Log("writing %s failed", dest)
		return
	}
	_ = fsx.SetOwnerMode(dest, extOwner(owner), 0o660)
	s.restorecon(false, dest)
	_ = m.set(want, key)
	s.Log("seeded config/%s/%s.opt", core, name)
}

// SeedLook: how games look (Base/retroarch/look): the sharp bilinear shader for every
// core, 3x for the Game Boy and a few shaders to pick from, next to retroarch.cfg. Only on
// installs whose retroarch.cfg we wrote: that one turns shaders on and points RetroArch at
// these folders. A newer image updates a file while it is unchanged; a file the user
// changed or deleted stays that way.
func (s *Setup) SeedLook() {
	src := s.Base + "/retroarch/look"
	own := s.raOwn()
	if !sys.IsDir(src) || !sys.IsDir(s.raExt()) || !sys.Exists(own+"/look") {
		return
	}
	owner, err := fsx.OwnerOf(s.raData())
	if err != nil {
		return
	}
	m := s.raManifest()
	m.touch()
	// a new retroarch.cfg starts over: files removed before come back
	if fsx.ReadTrim(own+"/look") == "new" {
		_ = m.dropContaining(" look/")
		_ = fsx.WriteLine(own+"/look", "seeded")
	}
	var files []string
	_ = filepath.WalkDir(src, func(p string, d os.DirEntry, err error) error {
		if err == nil && d.Type().IsRegular() {
			files = append(files, p)
		}
		return nil
	})
	sort.Strings(files)
	for _, f := range files {
		rel := strings.TrimPrefix(f, src+"/")
		key := "look/" + rel
		dest := s.raExt() + "/" + rel
		if sys.Exists(dest) {
			if fsx.SameContent(dest, f) {
				continue
			}
			cur, _ := fsx.SHA1(dest)
			if !m.has(cur, key) {
				s.Log("keeping user provided %s", rel)
				continue
			}
		} else if m.hasKey(key) {
			// we put it there before, so the user removed it
			continue
		}
		// missing folders, made the way vold makes them
		dir := s.raExt()
		if i := strings.LastIndexByte(rel, '/'); i > 0 {
			for _, part := range strings.Split(rel[:i], "/") {
				dir += "/" + part
				if sys.IsDir(dir) {
					continue
				}
				if err := fsx.MkdirAs(dir, extOwner(owner), extDirMode); err != nil {
					break
				}
				s.restorecon(false, dir)
			}
		}
		if err := fsx.CopyAtomic(f, dest); err != nil {
			s.Log("writing %s failed", dest)
			continue
		}
		_ = fsx.SetOwnerMode(dest, extOwner(owner), 0o660)
		s.restorecon(false, dest)
		sum, _ := fsx.SHA1(dest)
		_ = m.set(sum, key)
		s.Log("seeded %s", rel)
	}
	_ = fsx.Chown(m.path, owner)
}

// raLanguage is RetroArch's number (user_language) for the system languages it needs our
// fonts or our help for, "" for the others. Indonesian: Android reports it as "in",
// which RetroArch doesn't know.
func raLanguage(loc string) string {
	is := func(l string) bool { return loc == l || strings.HasPrefix(loc, l+"-") }
	switch {
	case is("ru"):
		return "9"
	case is("zh-TW") || is("zh-HK") || is("zh-MO") || is("zh-Hant"):
		return "11"
	case is("zh"):
		return "12"
	case is("pl"):
		return "14"
	case is("tr"):
		return "18"
	case is("in") || is("id"):
		return "24"
	}
	return ""
}

// RAFonts: RetroArch's menu (RGUI) draws Russian, Chinese, Polish and Turkish only with
// font files from its assets folder. Copies them there, points retroarch.cfg at that
// folder while it names none (started from Daijishou RetroArch gets no folders at all),
// and fixes the menu language once.
func (s *Setup) RAFonts() {
	data := s.raData()
	fontsSrc := s.Base + "/retroarch/rgui-font"
	if !sys.IsDir(data) || !sys.IsDir(fontsSrc) {
		return
	}
	owner, err := fsx.OwnerOf(data)
	if err != nil {
		return
	}
	own := s.raOwn()
	assets := data + "/assets"
	fonts := assets + "/rgui/font"
	_ = os.MkdirAll(own, 0o700)
	s.raManifest().touch()
	for _, dir := range []string{assets, assets + "/rgui", fonts} {
		if sys.IsDir(dir) {
			continue
		}
		if err := fsx.MkdirAs(dir, owner, 0o700); err != nil {
			s.Log("making %s failed", dir)
			return
		}
	}
	bins, _ := filepath.Glob(fontsSrc + "/*.bin")
	sort.Strings(bins)
	for _, b := range bins {
		if sys.IsFile(b) {
			s.seedOwned(b, fonts+"/"+baseOf(b))
		}
	}
	_ = fsx.ChownR(fonts, owner)
	_ = fsx.ChownR(own, owner)
	dests, _ := filepath.Glob(fonts + "/*.bin")
	for _, d := range dests {
		_ = os.Chmod(d, 0o600)
	}
	s.restorecon(false, assets, assets+"/rgui")
	s.restorecon(true, fonts, own)

	cfg := s.raCfg()
	if !sys.IsFile(cfg) {
		return
	}
	// unset, or "default" (what RetroArch writes back when it had no folder)
	cur := cfgGet(cfg, "assets_directory")
	if cur == "" || cur == "default" {
		if err := s.cfgSet(cfg, "assets_directory", assets); err != nil {
			s.Log("writing retroarch.cfg failed")
			return
		}
		s.Log("RetroArch's assets folder is %s now (was: %s)", assets, orUnset(cur))
	}

	// The language, once per RetroArch install. RetroArch takes the system language
	// itself on its first start, but not Indonesian, and it saves English for good when
	// the fonts were missing. So unset or English in a system language from raLanguage
	// gets that language, one time only: English picked in RetroArch after that stays.
	if sys.Exists(own + "/language") {
		return
	}
	loc := s.Props.Get("persist.sys.locale")
	lang := raLanguage(loc)
	if lang == "" {
		return
	}
	cur = cfgGet(cfg, "user_language")
	if cur == "" || cur == "0" {
		if err := s.cfgSet(cfg, "user_language", lang); err != nil {
			s.Log("writing retroarch.cfg failed")
			return
		}
		s.Log("RetroArch's menu language is %s now, for %s (was: %s)", lang, loc, orUnset(cur))
	}
	_ = fsx.WriteLine(own+"/language", loc)
	_ = fsx.Chown(own+"/language", owner)
}

func orUnset(v string) string {
	if v == "" {
		return "unset"
	}
	return v
}

// managed sets a retroarch.cfg setting when it is unset, what we wrote last time, or
// (before we ever wrote it) one of RetroArch's defaults. Anything else is the user's and
// stays, unless forced. What we wrote is kept in State/retroarch-managed/<key>.
func (s *Setup) managed(cfg, key, want string, force bool, defaults ...string) {
	dir := s.state("retroarch-managed")
	_ = os.MkdirAll(dir, 0o700)
	mark := dir + "/" + key
	cur := cfgGet(cfg, key)
	if cur == want {
		_ = fsx.WriteLine(mark, want)
		return
	}
	ours := false
	switch {
	case force || cur == "":
		ours = true
	case sys.IsFile(mark):
		ours = cur == fsx.ReadTrim(mark)
	default:
		for _, d := range defaults {
			if cur == d {
				ours = true
			}
		}
	}
	if !ours {
		return
	}
	if err := s.cfgSet(cfg, key, want); err != nil {
		s.Log("writing %s failed", key)
		return
	}
	_ = fsx.WriteLine(mark, want)
	s.Log("RetroArch: %s = %s (was: %s)", key, want, orUnset(cur))
}

// the built-in pad, 0x484b:0x1100 (odroidgo3-joypad, "GO-Super Gamepad")
const builtinPad = "484b:1100"

// RetroArchPlayers: the built-in pad is player 1 in RetroArch, external pads player 2 and
// up, in the order their first button is pressed. With persist.andr36oid.ra_player1 =
// external (Settings > Controllers) it is the other way round. RetroArch's device
// reservation does this; "reserved" keeps that player free for the pad until it sends its
// first input. Also for every pad: the menu and quit combos, and every player can use the
// menu. force (the setting was just changed in Settings) overrides values the user set in
// RetroArch itself for the two player settings.
func (s *Setup) RetroArchPlayers(force bool) {
	cfg := s.raCfg()
	if !sys.IsFile(cfg) {
		return
	}
	p1, p2, t1, t2 := builtinPad, "", "2", "0"
	if s.Props.Get("persist.andr36oid.ra_player1") == "external" {
		p1, t1 = "", "0"
		p2, t2 = builtinPad, "2"
	}
	s.managed(cfg, "input_player1_device_reservation_type", t1, force, "0")
	s.managed(cfg, "input_player1_reserved_device", p1, force, "")
	s.managed(cfg, "input_player2_device_reservation_type", t2, force, "0")
	s.managed(cfg, "input_player2_reserved_device", p2, force, "")
	s.managed(cfg, "all_users_control_menu", "true", false, "false")
	// 4 = Start+Select, with quit_press_twice (RetroArch's default) on
	s.managed(cfg, "input_quit_gamepad_combo", "4", false, "0")
	// RetroArch on Android swaps OK and back in its menu by default. Unswapped, A
	// confirms and B goes back, as printed on the pad and everywhere else on the console.
	s.managed(cfg, "menu_swap_ok_cancel_buttons", "false", false, "true")
}

// missingButtons: the buttons this console lacks (fn,l3,r3,l2,r2,vol), from
// Settings.Global andr36oid_missing_buttons once Quick start or Settings > Controls
// asked, before that from the hint in the joypad's device tree node.
func (s *Setup) missingButtons() string {
	m, _ := s.Settings.GetRaw("global", "andr36oid_missing_buttons")
	if m == "null" {
		b, _ := os.ReadFile(s.DTRoot + "/odroidgo3-joypad/andr36oid,missing-buttons")
		m = strings.ReplaceAll(string(b), "\x00", ",")
	}
	m = strings.ReplaceAll(strings.TrimRight(m, "\n"), " ", "")
	return strings.ToLower(m)
}

// RAHotkeys: the seeded fast forward hotkey is Select + L3. On a console without L3 it
// moves to Select + R2, or Select + Right without R2 as well, and back to L3 once the
// console has it again. Only while the hotkey is still what the seed or this wrote.
func (s *Setup) RAHotkeys() {
	cfg := s.raCfg()
	if !sys.IsFile(cfg) {
		return
	}
	missing := "," + s.missingButtons() + ","
	want := "106"
	if strings.Contains(missing, ",l3,") {
		want = "105"
		if strings.Contains(missing, ",r2,") {
			want = "22"
		}
	} else if !sys.IsFile(s.state("retroarch-managed/input_toggle_fast_forward_btn")) {
		// never moved on this console: nothing to do
		return
	}
	s.managed(cfg, "input_toggle_fast_forward_btn", want, false, "106")
}

// retroArchRunning: RetroArch writes all its settings back when it quits.
func (s *Setup) RetroArchRunning() bool {
	_, err := s.R.Run("pidof", raPkg)
	return err == nil
}

// lines of a text file, "" lines and # comments skipped, first word of each
func firstWords(path string) []string {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) == 0 || strings.HasPrefix(fields[0], "#") {
			continue
		}
		out = append(out, fields[0])
	}
	return out
}
