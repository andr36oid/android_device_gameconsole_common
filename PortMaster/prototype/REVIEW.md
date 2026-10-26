# PortMaster self-consistency review

2026-10-26. Sol. One local review of human commit `78b1c47` on
`prototype/portmaster-handoff`, followed by minimal staged, uncommitted fixes.
No new feature, hardware experiment, commit or network operation was performed.

Source paths below are relative to this prototype directory. Report paths are
relative to `/andr36oid/work/reports/portmaster/`. That report's
`prototype/REVIEW.md` links to this file. Historical raw logs, JSON records and
the pass-2 source patch are retained as historical evidence.

Severity: high blocks treating the guest/integration draft as deployable;
medium changes a security scope or substantive design claim; low affects
documentation, provenance or reporting. Recommendations below are existing host
implementation decisions, not new hardware questions. G1 remains the first
device procedure, for a human at a later time.

## Issues found

1. **Location:** `portmaster.te`, DRM xperms; report `design.md`, permissions table.
   **Contradiction:** one shared controller/guest list granted 28 requests while
   the CPU probe issues only 12. The broader GBM/SDL candidates were not scoped
   separately from the probe/controller contract.
   **Severity:** medium.
   **Fix applied:** split the lists. Controller requests exactly match the probe;
   the guest retains the existing candidate superset. No ioctl number was added.

2. **Location:** `portmaster.rules`, service-status contexts;
   `system/sepolicy/public/property_contexts:239` in the Android source tree.
   **Contradiction:** SF already has an exact `init.svc.surfaceflinger` mapping
   to `exported2_default_prop`; appending the draft's different exact mapping
   would duplicate the key. A successful `.te` compile does not test this.
   **Severity:** high.
   **Fix applied/recommended:** marked the entry as a proposed replacement,
   never an append. Human must choose retaining the existing type with reviewed
   read access or replacing it while preserving existing readers. No active
   context or permission was changed.

3. **Location:** report `integration-notes.md`, Java setter; `portmaster.te`.
   **Contradiction:** SystemUI is `platform_app`, while the draft authorizes
   `system_server` for request writes/status reads. The direct Java snippet
   cannot use that policy. This conflict was already disclosed in the package.
   **Severity:** high.
   **Recommendation:** retain the documented caller-boundary decision, preferably
   a permission-checked broker. No app-domain grant or broker was invented.

4. **Location:** `launch-flow.sh`, helper/audio/legacy-composer service lists;
   `portmaster.rules`, display control contexts; report `integration-notes.md`.
   **Contradiction:** the shell guest flow can control input/audio services and
   the legacy `hwcomposer-2-1`, while the policy only types SF and
   `vendor.hwcomposer-2-1`. It is not an enforcing implementation of that flow.
   **Severity:** high.
   **Recommendation:** keep the existing unconditional entry refusal and native
   implementation requirement. Resolve the fixed service/control contract there;
   do not add broad ctl grants. The diagnostic fallback is not a policy grant.

5. **Location:** `launch-flow.sh`, quiesce/stop loop; report `design.md`, steps 3-4.
   **Contradiction:** quiesce/mute ran before stopping input helpers, whereas the
   design stops input helpers first and then mutes/stops audio.
   **Severity:** medium.
   **Fix applied:** run quiesce at the audio boundary of the same stop loop,
   after input stops and before audio/display stops. Partial hook failure still
   calls restore and restores the originally running input helpers.

6. **Location:** `launch-flow.sh --guest`, HOME; report
   `prototype/rootfs-assembly.md`, guest paths; `portmaster.rules`, uid contract.
   **Contradiction:** HOME was `/root` despite proposed guest uid/gid 2906 and
   the writable `/home/portmaster` state bind.
   **Severity:** medium.
   **Fix applied:** HOME is `/home/portmaster`. Numeric uid/gid 2906 is otherwise
   consistent throughout the package and remains a proposed allocation.

7. **Location:** `launch-flow.sh` and `chroot-hooks.example.sh`, mount TODOs;
   report `open-questions.md`, Q6; `portmaster.rules`, proc restriction.
   **Contradiction:** TODOs suggested guest procfs and Q6 requested guest
   `/proc/*/fd`, while the contract forbids exposing host procfs and starts
   without guest procfs because PID namespaces are unavailable.
   **Severity:** medium.
   **Fix applied:** TODOs now say no guest procfs/read-only facts. Q6 inspects
   guest PIDs from the Android-side supervisor/root console instead.

8. **Location:** `launch-flow.sh --chroot/--guest`,
   `chroot-hooks.example.sh`; `portmaster.rules`; report rootfs recipe.
   **Contradiction:** the legacy shell checks an already populated root before
   its private mount hook and invokes `/usr/local/sbin/portmaster-enter`.
   The specified native design mounts the immutable image privately, drops
   privileges in the child and execs `/usr/local/libexec/portmaster-guest-entry`.
   Neither entry helper exists; changing just its pathname would not fix this.
   **Severity:** high.
   **Fix applied/recommended:** explicitly marked the legacy helper TODO for
   replacement. Leave the shell entry refused; implement the already specified
   native sequence before claiming the assembled image can use this launch path.

9. **Location:** `launch-flow.sh`, guest/parent cleanup; report `design.md`, return path.
   **Contradiction:** failed unmount becomes only a child exit code. Parent
   cleanup can start display owners and remove its lock if its restore hook
   succeeds. It checks surviving `kms-probe` only in probe mode, not guest
   descendants/fds. This cannot guarantee the design's requirement to release
   resources before restore on every guest failure path.
   **Severity:** high.
   **Recommendation:** native recovery must acknowledge unmount/resource release,
   retain failed-session state and track escaped descendants before handback.
   Keep the current refusal gates; no second cleanup engine was added. The
   controller and rc delegate to the existing flow and duplicate no teardown.

10. **Location:** report `design.md`, audio step and original-state restoration;
    `launch-flow.sh`, audio list; Android `audioserver.rc`, stopped/running triggers.
    **Contradiction:** the design described only a stop cascade. In this tree
    the stopped trigger stops then starts the HALs, and the running trigger also
    starts them. HALs originally stopped can become running, violating the
    original-state promise even though the shell's start journal excludes them.
    **Severity:** high.
    **Fix applied/recommended:** corrected the source description and added a
    shell TODO. Human must reconcile the existing triggers with the native
    original-state contract. No audio trigger or guessed recovery behavior changed.

11. **Location:** report `design.md`, UI states; `session-controller.sh` and
    `portmaster-session.rc`, literal handshake.
    **Contradiction:** conceptual entering/running/restoring names could be
    mistaken for the actual published property values.
    **Severity:** low.
    **Fix applied:** design now distinguishes conceptual UI states from literal
    request/status values and says that no current path publishes `ready`.

12. **Location:** `hardware-test.sh`, `finish()` result-template generation.
    **Contradiction:** the template captured Driver exit before pending recovery
    or lock cleanup could change the final exit to failure.
    **Severity:** low.
    **Fix applied:** write the template after those final exit decisions, so its
    exit agrees with the console and process result.

13. **Location:** report `prototype/runbook.md`, expected output; `kms-probe.c`;
    `hardware-test.sh`, console/log split.
    **Contradiction:** the handoff.log example included driver-only RESULT and
    OBSERVE lines, and did not explain additional disconnected CONNECTOR lines.
    **Severity:** low.
    **Fix applied:** separated the driver console block and explained that each
    enumerated connector is printed. Probe MASTER/CONNECTOR/MODE/PATTERN/RESTORE,
    MASTER dropped, DRM closed and EXIT spellings match the C source; no C
    output change was needed. Exit 7 remains a failed prior-state restore even
    when fallback disable and Android return succeed.

14. **Location:** `handoff-watchdog.sh`, introductory comment; report runbook.
    **Contradiction:** the comment required a second root ADB session, while the
    one-command driver detaches and arms the guard itself.
    **Severity:** low.
    **Fix applied:** comment describes the detached driver invocation.

15. **Location:** report `STATUS.md`, `EXECUTIVE-SUMMARY.md`,
    `prototype/build-notes.md`, `prototype/runbook.md`.
    **Contradiction:** current prose still described HEAD `89a7de0`, fourteen
    uncommitted files or no commit, after the human's `78b1c47` commit. STATUS
    also called invalid CLI-argument tests malformed property requests.
    **Severity:** low.
    **Fix applied:** current provenance names `78b1c47` and subsequent staged
    corrections; STATUS names malformed command arguments. Earlier dated pass
    records remain historical. STATUS links to this review.

16. **Location:** report `prototype/artifacts/SHA256SUMS`,
    `prototype/validation-pass2.md`, `prototype/source-review.patch`.
    **Contradiction:** validation prose described the twelve-file manifest/patch
    as current after two entry files and this review's corrections.
    **Severity:** low.
    **Fix applied:** refreshed the manifest for all fourteen source/test files
    plus the unchanged binary. Labeled pass-2 validation and its twelve-file
    patch historical. Use `78b1c47` plus the staged diff for current sources;
    raw old validation results were not rewritten.

17. **Location:** report `findings-stack.md`, ownership/OpenWrt/implication sections;
    report `design.md` and OpenWrt audit.
    **Contradiction:** preliminary findings assigned drmhwc to SF, called the
    configured driver stack working, described the OpenWrt kernel as 6.10 and
    said the tree had no prototype. Later source/audit work superseded those claims.
    **Severity:** medium.
    **Fix applied:** corrected owner/runtime certainty and 6.10.0-rc4 banner;
    marked the inventory as predating the prototype and preserved the separate
    SD-image versus incomplete glibc chroot distinction.

18. **Location:** report `findings-web.md`; report `design.md`, GL/rootfs sections.
    **Contradiction:** preliminary blanket claims included no Weston, generic
    SDL fbdev support, SF-only release, best performance and donor blobs using
    the same kbase driver. The final design explicitly does not establish these.
    **Severity:** medium.
    **Fix applied:** qualified catalog/prior-art claims and aligned approach A
    with the named bookworm/Mali/KMSDRM candidate and unmeasured outcomes.

## Cross-checks without a contradiction

- Property types/keys agree: `portmaster_request_prop` is
  `sys.portmaster.request`; `portmaster_status_prop` is `sys.portmaster.status`.
  Init consumes `enter` as `idle`; controller publishes `preflight`,
  `unavailable`, `handoff`, `returned-unverified` or `recovery-required`.
  `portmaster_display_ctl_prop` labels the four SF/vendor-composer start/stop
  keys; it is not a third `sys.portmaster.*` key.
- Source/init names agree on `surfaceflinger` and `vendor.hwcomposer-2-1`.
  Display stop is SF then composer; restore is original composer then SF.
  The unprefixed candidate remains diagnostic only. The request chain is
  rc -> controller -> existing launch flow -> gated guest child; no shipping
  native binary, socket, adapter or UI is claimed to exist.
- Compiled kernel DRM macros confirm the exact controller/probe set:
  `641e 641f 64a0 64a1 64a2 64a6 64a7 64ae 64af 64b2 64b3 64b4`.
  The guest's 28 candidates also map to real local DRM/Rockchip UAPI requests.
  No missing probe request or bogus DRM number was found.
- All 25 Mali candidates map to real common/JM UAPI type/nr values, including
  reserved version check `8034`. No CSF/counter/test family was accidentally
  listed. The absent `libmali-bifrost-g31-rxp0-gbm.so` binary cannot supply an
  actual ioctl trace; its filename does not prove ABI compatibility. This was
  already an explicit Q3 gate. Kept the candidate set and clarified its comment
  rather than guessing a blob-specific allowlist.
- Design, recipe, summary, STATUS and Q5 agree on bookworm arm64 glibc 2.36,
  Python 3.11, the exact Rockchip blob name/revision, vendor-built SDL2 2.26.5
  KMSDRM/GLES, per-port GL4ES candidate 1.1.6 and matching unprivileged gptokeyb.
  uid/gid 2906 remains proposed, not allocated or implemented by shell.
- Capacity figures agree: component limits sum to 380 decimal MB;
  448 MiB = 469.762 MB; writable reserve 128 MB; active plus reserve about
  598 MB, two roots plus reserve 1,068 MB, three images plus reserve 1,537 MB;
  require 2 GiB free plus games/runtimes. Prior-art and OpenWrt sizes describe
  different artifacts. No assembled-rootfs measurement is claimed.
- Review-ready files and G1 scripts/artifact exist. Rootfs assembly, missing
  archive pins, native boundary and complete policy/context integration remain
  unfinished host work. Visible return, acceleration, lifecycle, input/audio,
  enforcing execution and recovery remain marked as device-dependent. No
  ready property or passed hardware result is claimed.

## Local validation of corrections

Passed the existing 21 DRM fault, 9 launch, 3 guard and 3 driver mock cases;
the policy checker rejects the unchanged baseline as expected and compiles its
temporary composition with the three documented platform rule changes and
neverallows enabled. ShellCheck, POSIX shell syntax and `host_init_verifier`
passed. Compiled DRM macro comparison confirms exact controller/probe equality;
all Mali candidates were checked against local common/JM definitions.

Two additional temporary mocks verified input-stop-before-quiesce with rollback
on a failing hook, and matching template/console/process failure status with a
retained lock on pending recovery. No guest or hardware execution occurred.
The final source hash manifest and staged whitespace checks passed.
The Java snippet and full product/context integration remain uncompiled here.
