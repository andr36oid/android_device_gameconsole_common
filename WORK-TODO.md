# andr36oid work TODO (overnight 2026-10-31)

Scratch tracker for parallel tasks. Untracked file - do NOT commit (docs-outside-repos).
Updated as tasks move. Activity narration: /andr36oid/work/reports/claude-activity.log

## IN PROGRESS
- (nothing actively running; all autonomous runway exhausted - see BLOCKED)

## QUEUED (start only after OkRoll integration is reviewed)
- [ ] **Clone issue tracker** -> /andr36oid/issues/clones/<N>/
      Enumerate open clone BOOT issues from GitHub (read-only `gh`, NO comments) + agent-notes.
      One focused sol per ticket, each in its own overlay worktree (ovl-worktree.sh).
      Each produces, INTO the issue folder (report + deliverable, NOT chat, NOT GitHub):
        - diagnosis (fake card / wrong profile), OR
        - DTB boot-files zip (standard on-BOOT names), OR
        - a fix to our builds (new/corrected DTS + profile).
      I verify each agent's work before marking the ticket done.
      Known live: #2 Soy Y3506-V03, #3 R36 Ultra X GRC802, #4 Y3506 no-boot, #5, #8 (refresh fresh).
      When all done: one-line pointer to the folder tree only.

## BLOCKED (needs the SD card moved to the flash-host reader - user asleep)
- [x] Device back on adb: measured 0221 warm baseline on the acceptance console (zygote ~17s,
      clean boot, pipeline proven). work/reports/subsystem-complete/boot-measure-device/RESULT.md
- [ ] **Boot-speed: COLD first-boot comparison of 0347** - the only remaining goal step. Needs a
      fresh flash with /data wipe (card in reader), which adb cannot do. One-step morning plan in
      RESULT.md. dexpreopt's payoff is the cold/first boot; warm reboots are already ~17s.
- [ ] **Push 0f1742f** (boot-speed trims + dexpreopt + instrumentation) - ONLY after the
      hardware boot-time result confirms it. Unpushed by design (FOR_CLAUDE.md gate).
- [ ] **Push f8b3fab** (dingux quote repair) - after hardware-confirming dingux controls.

## DONE
- [x] OkRoll boot-files drop-in zip (stock DTB, 7 standard on-BOOT names):
      /andr36oid/oddcombo/andr36oid-okroll-clone-boot-files.zip (sha cf2ca21b...)
- [x] OkRoll console identity writeup (given in chat as GitHub markdown).
- [x] Boot-speed code: 0f1742f (init trims, WITH_DEXPREOPT warm caches, pm.dexopt), build 0347 (65 odex).
- [x] Boot sol: Task A instrumentation applied (boot-timing.txt collector); Task B = no safe
      concurrency conversions (bottleneck is dex/zygote, not serial init). 3 files in ProblemReport/, uncommitted.
- [x] Baseline extracted from archived boots (no device needed).
- [x] Reminder set: 8:57am Nov 1 to review fixes + posts together (cron 1193e123, session-only).
- [x] **OkRoll profile added to builds**: rk3326-r36s-clone-okroll.dts + kernel Makefile +
      Devices/OkRoll-Clone/ (7 on-BOOT names + README) + experimental-dtbs/list.txt +
      andr36oid-profiles.txt (experimental) + DtbHint mapping/tests. DTB builds clean (77856 B),
      DtbHint 2331 checks + 87 stock-validation checks pass. Report: okroll-profile-sol.md.
      CAVEAT: right stick dead until the 4-axis driver lands (in progress). Many items need hardware
      (orientation, polarity, LEDs, buttons, wifi/bt) - all disclosed in the profile README.
- [x] **OkRoll 4-axis driver**: adc-joystick.c extended with optional joy_rx/joy_ry -> ABS_RX/RY,
      gated on io-channel-names. Backward-compat PROVEN (13 byte-identical legacy traces +
      source-equivalence); existing 2-ch consoles register only ABS_X/Y. Compiles clean, checkpatch 0.
      Report: okroll-adc4-sol.md. Right-stick polarity/range need hardware.
- [x] **Clone issue tracker DONE**: #3/#7/#8/#9 each have verified diagnosis + deliverable in
      /andr36oid/issues/clones/<N>/; #10 OkRoll done. Index at issues/clones/INDEX.md. #7 soundpicker
      = verified real build bug. All boot-files zips validated, all DTBs compile. Promotions staged, unpushed.
- [x] BONUS (from OkRoll run): fixed 3 pre-existing DtbHint wiring gaps - EE-V12-Var1-Panel3,
      R36S-Panel4-Type1, KirbX-Clone were missing from the helper lists; now wired.

## DEFERRED / DAYTIME
- [ ] Long-tail emulator controls hardware verify (PPSSPP/ScummVM/OpenBOR/flycast/dingux) - needs live session.
- [ ] terra non-breaking flags: PPSSPP/ScummVM GUID coverage, hw:0,0 ALSA hardcode.
- [ ] Build hygiene: bump ro.product.build.version.incremental per build (hotfix-keying gotcha).
- [ ] GA/OTA decision for the boot-speed image.

## HARD CONSTRAINTS (do not drift)
- adb serial ALWAYS c3d9b8674f4b94f6; NEVER the rabbit 919129A6916002729431.
- Author itskenny0 <0@kenny.cat>; NO AI attribution lines. Subject-only plain commit msgs.
- No commit/push without maintainer approval (FOR_CLAUDE.md). sol/terra/luna never touch git/GitHub.
- Never comment on GitHub issues/PRs. No real `sync` on build host. No `--verify`/readback on flashes.
- Preserve untracked: hotfix/tools/.gitignore, kernel rk3326-r36s-clone-soysauce-v03.dts.
- codex: gpt-6.1-sol (hard), gpt-5.6-luna (chores); invoke with stdin from /dev/null.
