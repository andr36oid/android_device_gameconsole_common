# Subsystem Setup

Platform-signed, system-UID Settings page under Settings > System. The existing
wizard blurb, download flow, hint bar and SubsystemBridge launches remain in place.
The app enumerates immediate `.img` files on the selected EASYROMS games card on a
worker executor; the root broker validates and resolves them for both scan and run.
Other cards and internal storage are not automatic fallbacks.

“Active dArkOSen image” includes Automatic, originals, and completed optimized
copies. Automatic chooses the first usable original in UTF-8 byte order, falling
back to a completed optimized file only if no original is usable. An explicit
missing selection is retained until its card/file returns or the user selects
Automatic or another image. Read-only cards support discovery and launch; downloads
and optimization require a writable card.

`persist.sys.andr36oid.subsystem.selected` is authoritative. It stores lowercase
SHA-256 of the lowercase filesystem UUID, one NUL byte, and the exact UTF-8 basename.
`selected_image_id`, `selected_image_name` and `selected_image_uuid` in
`subsystem_setup` are display caches, never a source for restoring properties.
Shared vectors live in `../PortMaster/erofs-tools/selection-vectors.tsv`.

A selected original normally uses its own valid `.erofs.img` companion. “Use
optimized copy when available” writes
`persist.sys.andr36oid.subsystem.prefer_erofs` (default 1); turning it off runs the
original. Selecting a completed optimized file directly always uses that file.
The summary shows both selected and effective files. Selection/preference changes
wait for a newer scan sequence with matching inputs before enabling launch.
Storage events, resume, chooser opening, completed downloads and completed jobs
refresh enumeration and request a scan even after an old availability value of 0.
Init coalesces a re-scan if its scan oneshot was already running.

Optimize creates a separate compressed root filesystem beside the original. It
never replaces the original or includes the live writable overlay/games binds.
Allow about 10–30 minutes; this is a planning estimate, not a hardware measurement.
The page shows an indeterminate spinner, phase, bytes, elapsed time and space
budget. The job ID must be acknowledged by the root worker before progress is
accepted. The request pins `opt_source` and a fresh 16-hex-character
`opt_request_id`. A second operation is disabled while a session/job/download is active.
The activity keeps the screen on while visible; an independently renewed, timed
kernel wake lock keeps the worker running when the page is closed. Reopening
reattaches to the existing job rather than requesting another conversion.

The broker owns a root-only operation lock and journals under
`/data/misc/andr36oid-subsystem/optimize`. Scan reconciles dead workers by boot ID
and process start time. Staging files never end in `.img`; only identity-checked
job-owned staging files and proven orphan records may be reclaimed. A valid final
image plus matching record can reconcile as completed after an interrupted final
notification. Foreign/malformed records and unrelated outputs are retained and
reported as name collisions. Rename or remove a stale companion and its `.meta`
record deliberately before rebuilding; rename a completed pair together if it
should remain directly selectable.

Writable state is on `/data`, under the original family ID and source generation.
An original and its valid companion share that state; different sources and
changed generations get different state. Legacy `upper` stays at its original
path. Only a matching sole original, an old successful-mount log, and a clean
OverlayFS metadata inspection allow it to be associated automatically. Ambiguous
or unsupported old state is retained for an eventual explicit migration UI.

Downloads still require a configured HTTP(S) URL in
`persist.andr36oid.subsystem.image_url`; no URL is shipped. DownloadManager writes
`darkosen.img.download`, and this page finalizes successful downloads on its next
visit without changing an explicit selection. API 29/legacy storage remains
necessary for this Android 11 DownloadProvider destination.

This is a review draft. USB card-ownership locking and hardware validation are
follow-ups: enforcing SELinux/storage/wake-lock access, actual EROFS + overlay
mounts, B1 sessions and bridge launches, power-loss/card-removal recovery, memory,
performance, and LZ4 versus LZ4HC timing still need device checks. Only English
resources were changed; translations are handled separately.
