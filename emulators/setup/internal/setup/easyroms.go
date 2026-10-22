package setup

import (
	"bufio"
	"bytes"
	"io"
	"os"
	"strconv"
	"strings"
	"time"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

// Init selects partition 7 of the boot/data disk, checked by its GPT name. ready with an
// empty value means there is no EASYROMS; only older boot paths use the fixed defaults.
func (s *Setup) easyromsProp(name, fallback string) string {
	v := s.Props.Get("sys.andr36oid.easyroms." + name)
	if v == "" && s.Props.Get("sys.andr36oid.easyroms.ready") != "1" {
		return fallback
	}
	return v
}

// easyromsMounted is where vold mounted EASYROMS (from /dev/block/vold/public:<maj>,<min> at
// /mnt/media_rw/<uuid>), "" if it isn't. /proc/mounts tells: asking `sm list-volumes`
// started a Java VM each time.
func (s *Setup) easyromsMounted() string {
	id := s.easyromsProp("id", "public:179,7")
	if id == "" {
		return ""
	}
	b, err := os.ReadFile(s.Mounts)
	if err != nil {
		return ""
	}
	sc := bufio.NewScanner(bytes.NewReader(b))
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) < 2 || f[0] != "/dev/block/vold/"+id {
			continue
		}
		dir := f[1]
		if strings.HasPrefix(dir, s.MediaRW+"/") && len(dir) > len(s.MediaRW)+1 && sys.IsDir(dir) {
			return dir
		}
	}
	return ""
}

// easyromsPath waits up to wait for EASYROMS to be mounted.
func (s *Setup) easyromsPath(wait time.Duration) string {
	end := s.Now().Add(wait)
	for {
		if dir := s.easyromsMounted(); dir != "" {
			return dir
		}
		if !s.Now().Before(end) {
			return ""
		}
		// requests that came in meanwhile (the app page) don't wait for EASYROMS
		s.poll()
		s.Sleep(time.Second)
	}
}

// FindEasyroms finds where EASYROMS is mounted, waiting up to wait, and whether the card
// has no EASYROMS at all. On a first start vold checks the new card for minutes before it
// mounts EASYROMS: then EasyromsLate() is true and the steps that need it run once the
// rest is done, when it shows up (the worker's late job).
func (s *Setup) FindEasyroms(wait time.Duration) {
	s.noroms, s.easyroms, s.easyromsLate = false, "", false
	if !sys.Exists(s.easyromsProp("part", s.EasyromsPart)) {
		s.noroms = true
		s.Log("no EASYROMS partition on this card (.noroms)")
		return
	}
	s.easyroms = s.easyromsPath(wait)
	if s.easyroms == "" {
		s.Log("EASYROMS not mounted yet, its steps wait for it")
		s.easyromsLate = true
	}
}

// EasyromsLate: EASYROMS wasn't mounted, its steps are left for the late job.
func (s *Setup) EasyromsLate() bool { return s.easyromsLate }

// romFolders are the first words of rom-folders.txt.
func (s *Setup) romFolders() []string { return firstWords(s.Base + "/rom-folders.txt") }

// RomNomedia: an empty .nomedia in each ROM folder on EASYROMS (ours and ArkOS-style ones
// the user copied). Android's media scanner then files the pictures, music and videos in
// there (box art, snaps, game files) as plain files, so Gallery and the music player
// don't fill up with game files. Only the media scanner reads .nomedia; Daijishou,
// RetroArch and PPSSPP skip hidden files. Each folder once per card: a .nomedia the user
// deletes stays deleted.
func (s *Setup) RomNomedia() {
	root := s.easyroms
	if root == "" || !sys.IsFile(s.Base+"/rom-folders.txt") {
		return
	}
	noted := s.state("rom-nomedia." + baseOf(root))
	if fsx.Touch(noted) != nil {
		return
	}
	seen := map[string]bool{}
	if b, err := os.ReadFile(noted); err == nil {
		for _, l := range splitLines(string(b)) {
			seen[l] = true
		}
	}
	var add []string
	for _, name := range s.romFolders() {
		if !sys.IsDir(root+"/"+name) || seen[name] {
			continue
		}
		nm := root + "/" + name + "/.nomedia"
		if sys.Exists(nm) || fsx.Touch(nm) == nil {
			add = append(add, name)
			seen[name] = true
		} else {
			s.Log("couldn't put .nomedia into %s/", name)
		}
	}
	if len(add) == 0 {
		return
	}
	f, err := os.OpenFile(noted, os.O_WRONLY|os.O_APPEND, 0)
	if err != nil {
		return
	}
	_, _ = f.WriteString(strings.Join(add, "\n") + "\n")
	f.Close()
}

// MakeRomFolders: the ROM folders from rom-folders.txt on a new EASYROMS, with a README,
// Game tips.txt and the free games. A card that already has a ROM folder layout (ArkOS,
// dArkOS, the user's own) is left exactly as it is. Once per card (its UUID).
func (s *Setup) MakeRomFolders() {
	list := s.Base + "/rom-folders.txt"
	if !sys.IsFile(list) || s.noroms {
		return
	}
	root := s.easyroms
	if root == "" {
		s.Log("EASYROMS not mounted, no ROM folders")
		return
	}
	uuid := baseOf(root)
	stamp := s.state("rom-folders." + uuid)
	if sys.Exists(stamp) {
		return
	}
	// set while we set up this card: a start cut off halfway through (e.g. while copying
	// the free games) finishes the job the next time, as the folders there are ours
	pending := s.state("rom-folders.pending." + uuid)
	names := s.romFolders()
	if !sys.Exists(pending) {
		for _, n := range names {
			if sys.IsDir(root + "/" + n) {
				s.Log("EASYROMS already has %s/, leaving its folders alone", n)
				_ = fsx.Touch(stamp)
				return
			}
		}
	}
	_ = fsx.Touch(pending)
	for _, n := range names {
		if err := os.MkdirAll(root+"/"+n, 0o755); err != nil {
			s.Log("couldn't make %s/ on EASYROMS", n)
			return
		}
	}
	// the list itself, without the comment lines, is the README
	var readme strings.Builder
	readme.WriteString("ROM folders made by andr36oid. Put each system's games into its folder.\n")
	readme.WriteString("Daijishou (the home screen) and RetroArch can then scan them.\n\n")
	if b, err := os.ReadFile(list); err == nil {
		for _, l := range splitLines(string(b)) {
			if l != "" && !strings.HasPrefix(l, "#") {
				readme.WriteString(l + "\n")
			}
		}
	}
	_ = os.WriteFile(root+"/README-andr36oid.txt", []byte(readme.String()), 0o644)
	// which heavy games need a setting, and which run too slow (game-compat)
	if tips := s.Base + "/game-tips.txt"; sys.IsFile(tips) {
		_ = fsx.CopyAtomic(tips, root+"/Game tips.txt")
	}
	s.Log("made the ROM folders on EASYROMS (%s)", uuid)
	// only on a card we just set up, so never on a card with the user's games
	s.seedFreeGames(root)
	_ = fsx.Touch(stamp)
	_ = os.Remove(pending)
}

// FreeGames are a few games whose authors allow sharing them (FreeGames/games.txt), so a
// new console has something to play before the user copies games. EASYROMS needs this
// much free space for them, in KB (about 30 MB unpacked).
const freeGamesMinFreeKB = 65536

// seedFreeGames copies each game into its ROM folder, and free-games-licenses.txt to the
// top. A file that is already there is never overwritten, and a game whose folder doesn't
// exist is left out.
func (s *Setup) seedFreeGames(root string) {
	archive := s.Base + "/free-games.tar.gz"
	if !sys.IsFile(archive) || root == "" || !sys.IsDir(root) {
		return
	}
	s.Progress.Stage("games")
	if free, err := s.freeKB(root); err == nil && free < freeGamesMinFreeKB {
		s.Log("EASYROMS has only %d KB free, no free games", free)
		return
	}
	// the whole archive is read once before anything is written: a broken one leaves
	// nothing behind
	members, err := tarGzList(archive)
	if err != nil || len(members) == 0 {
		s.Log("can't read %s", archive)
		return
	}
	want := map[string]bool{}
	for _, m := range members {
		if m == "" || strings.HasSuffix(m, "/") || strings.HasPrefix(m, "/") || strings.Contains(m, "..") {
			continue
		}
		if i := strings.LastIndexByte(m, '/'); i > 0 {
			if dir := m[:i]; !sys.IsDir(root + "/" + dir) {
				s.Log("no %s/ folder on EASYROMS, left out %s", dir, m[i+1:])
				continue
			}
		}
		dest := root + "/" + m
		// a copy cut off by a power loss on an earlier start
		_ = os.Remove(dest + ".part")
		if !sys.Exists(dest) {
			want[m] = true
		}
	}
	// then one pass that copies what is wanted
	n := 0
	err = tarGzEach(archive, func(name string, r io.Reader) error {
		if !want[name] {
			return nil
		}
		dest := root + "/" + name
		if err := s.copyTo(r, dest); err != nil {
			_ = os.Remove(dest + ".part")
			s.Log("couldn't copy %s to EASYROMS", name)
			return nil
		}
		n++
		return nil
	})
	if err != nil {
		s.Log("reading %s failed", archive)
	}
	s.Log("copied %d files (free games and their licences) to EASYROMS", n)
}

// copyTo writes r to dest through dest.part.
func (s *Setup) copyTo(r io.Reader, dest string) error {
	f, err := os.Create(dest + ".part")
	if err != nil {
		return err
	}
	n, err := io.Copy(f, r)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return err
	}
	s.Progress.Wrote(n)
	return os.Rename(dest+".part", dest)
}

// ReleaseCards tells StorageManagerService the start's steps are done, so it mounts the
// cards it held back (anything but the boot card; it gives up waiting after 10 minutes).
// The hold ends for good (persist.andr36oid.setup_done) only once Daijishou is set up: a
// start cut short or one that left Daijishou's platforms for the next start holds the
// cards again next time.
func (s *Setup) ReleaseCards() {
	const setupDoneProp = "persist.andr36oid.setup_done"
	if s.Props.Get(setupDoneProp) == "1" {
		return
	}
	if !s.noroms && sys.IsDir(s.djData()) && !sys.Exists(s.djDone()) {
		_ = s.Props.Set("sys.andr36oid.cards_free", "1")
		s.Log("Daijishou not set up yet, other cards may be mounted now but wait again next start")
		return
	}
	_ = s.Props.Set(setupDoneProp, "1")
	s.Log("first start's steps are done, other cards may be mounted now")
}

// EasyromsLateTries is how often the late job waits a minute for EASYROMS.
const EasyromsLateTries = 20

// RunEasyromsLate: the EASYROMS steps of a start that ended before vold mounted it: the
// ROM folders with the free games, their .nomedia files, the BIOS folder, Daijishou's
// systems. Waits up to about 20 minutes.
func (s *Setup) RunEasyromsLate() {
	s.Progress.Stage("storage")
	s.easyroms = ""
	for i := 0; i < EasyromsLateTries && s.easyroms == ""; i++ {
		s.easyroms = s.easyromsPath(time.Minute)
		s.poll()
	}
	if s.easyroms == "" {
		s.Log("EASYROMS still not mounted, its steps next start")
		s.ReleaseCards()
		return
	}
	s.easyromsLate = false
	s.Log("EASYROMS mounted at %s, doing its steps now", s.easyroms)
	s.Progress.Stage("folders")
	s.MakeRomFolders()
	s.RomNomedia()
	s.BIOSFolder()
	s.Progress.Stage("library")
	s.SeedDaijishou()
	s.DJNewGames()
	s.DaijishouReady()
	s.ReleaseCards()
}

func itoa(n int) string { return strconv.Itoa(n) }
