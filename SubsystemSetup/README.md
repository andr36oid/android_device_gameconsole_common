# Subsystem Setup

Platform-signed, system-UID Settings page under Settings > System. The activity and
code-built PreferenceFragment follow BlackFrameInsertion. Properties are written
directly, as in KernelTweaks, and launches use the bridge's target-then-request
sequence. The APK does not perform the root handoff or install a broker service.

Status reads `sys.andr36oid.subsystem.image`: `1` means installed, an empty value
means checking, and any other value means missing. The page requests `scan` once
while status is unknown, preserves that decision across recreation, and refreshes
every two seconds while resumed. A missing broker leaves the page at Checking.

Downloads require a validated active Internet connection and a configured HTTP
or HTTPS URL in `persist.andr36oid.subsystem.image_url`. No URL is shipped. The URL
must serve an uncompressed `.img` file. The destination is the EASYROMS volume
selected by `sys.andr36oid.easyroms.id`, with SaveBackup's legacy fallback for older
boots. No other card or internal storage is selected as a fallback.

The app targets API 29 and requests legacy storage because this Android 11 tree's
DownloadProvider otherwise rejects the custom `subsystem/darkosen/` destination.
The runtime minimum remains the platform default, also required by the hint bar.
Card permissions and DownloadManager delivery still need verification on hardware.
Storage or download failures show a toast with manual copying instructions.

DownloadManager writes `darkosen.img.download` so the broker cannot scan a partial
image as installed. While this page is open, it finalizes successful downloads as
`darkosen.img`, without replacing an existing file, then requests a new scan. If
the page was closed, reopen it to finalize the image. Failed staging files remain
on the card for manual inspection or removal. This is intentionally a minimal
download flow, without archive extraction, checksum verification, or a background
installation service. The Downloads record keeps the original staging filename.

The hosting URL and real translations are pending. All locale files intentionally
contain English with translation TODO comments. German translations must use
informal "du". There is no stub for the root broker in this APK; that is a separate
component. No full image build is needed to review this module.
