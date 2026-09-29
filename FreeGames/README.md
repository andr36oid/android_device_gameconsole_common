# Free games on a new card

A new console has nothing to play until the user copies games. So the image
carries a few games whose authors allow anyone to share them, and the
gameconsole-emu service copies them into the ROM folders it makes on a fresh
EASYROMS partition (`seed_free_games` in `emulators/seed/gameconsole-emu.sh`).

- Only on a card where the service just made the ROM folders. A card with an
  ArkOS or own folder layout gets nothing.
- A file that is already there is never overwritten. A deleted game is not
  put back.
- A game whose folder is not in `rom-folders.txt` is left out (Freedoom needs
  the `doom` folder and the PrBoom core from the O2 cores work).
- `free-games-licenses.txt` goes to the top of EASYROMS: every game, its
  authors, licence and source, and the licence texts. The same text is the
  NOTICE (Settings > About > Legal).
- `GAMECONSOLE_FREE_GAMES := false` leaves the archive out of the image.

Files:

| File | What |
|---|---|
| `games.txt` | the games: folder, name on the card, URL, sha256, licence, source |
| `build-free-games.sh` | downloads, checks the sha256s, writes `free-games.tar.gz` and `NOTICE.txt` (reproducible; `--check` compares) |
| `licenses/` | the upstream licence files |
| `source/` | the exact source of the GPL games (uCity 1.3, Thwaite 0.04), as GPL 3 asks; not installed |
| `tests/run-tests.sh [toybox]` | host tests in a temp folder, under bash, dash and busybox ash |

Adding a game: only with a licence that clearly allows redistribution for
everything in the ROM (code, graphics, music). No non-commercial licences, no
"free download" without a licence. Add a line to `games.txt`, its licence file
to `licenses/`, the source to `source/` if it is GPL, then run
`./build-free-games.sh` and `tests/run-tests.sh`.
