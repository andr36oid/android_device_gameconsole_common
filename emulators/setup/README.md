# Setup service: Ports library

Every boot (including a late EASYROMS mount), the setup service reconciles the
existing Daijisho Ports platform with its sync folders on the selected EASYROMS
card. New `.sh` and `.SH` files become game entries; removed scripts lose their
entries. Hidden files/directories and resource forks are ignored. Existing games
keep their IDs, titles, favorites and play history. Other platforms are untouched.
An unavailable card/folder or a deleted library/platform is left alone.

For an on-demand refresh after PortMaster, run as root on the device:

```text
/system/bin/gameconsole-setup reconcile-ports
```

This job uses the setup worker and runs only Ports reconciliation. It is independent
of the one-time seed markers and `persist.andr36oid.daijishou_autosync`. Daijisho is
restarted only when entries change, returning to home if it was in front. No
broker/init trigger is installed here.

Reconciliation also keeps a synthetic **PortMaster** game with a `file://` URI for
`/data/user/0/com.magneticchen.daijishou/files/gameconsole-ports/PM`. The setup
service creates this empty private marker because Daijisho checks that
`{file.path}` exists before launching a file-type game. The Ports player passes
that path as `-e ROM {file.path}`; `LaunchPortActivity.resolveSpec()` takes its
basename, `PM`, and requests PortMaster. It needs no launcher script or bridge
change. Both the marker and game entry are restored if deleted. Its URI is
included in the desired entries, so it survives removal of every actual port.

The initial seed creates platforms and players, then reconciliation inserts Ports
games using Daijisho 1.5.0's `PlayableItemEntity` defaults, SAF document URIs,
keyword JSON and first-4-MiB MD5. Ports additions do not request an additional
whole-library Sync; other systems retain the existing new-games prompt.
