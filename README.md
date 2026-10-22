# PopShare

ColorOS-style sharing for other Android phones: the app you share to opens in a floating
pop-up window (Samsung pop-up view / Android freeform) instead of taking over the screen, and
you stay in the app you shared from.

Built for One UI 8.5 (Android 16). The pop-up launch was verified by hand on that version; the app itself is experimental.

## How it works

1. You share something and pick **Pop-up share** (or a starred app's own entry) in the share sheet.
2. PopShare copies any shared files into its own storage, so the target app keeps access to them.
3. Through [Shizuku](https://github.com/thedjchi/Shizuku), PopShare runs
   `am start --windowingMode 5` on the chosen app, which opens it in a new pop-up window.
   Text, links and single files go this way; the pop-up belongs to the target app, so its next
   screens (e.g. WhatsApp's chat after you pick a contact) stay in the pop-up.
4. Several files at once can't be passed through `am`, so for those PopShare opens a tiny
   invisible activity of its own in the pop-up, which then starts the chosen app inside it.
   Some apps may move their follow-up screens out of that pop-up.

Files are granted to the target app by PopShare itself, so the shell never handles your content.

If Shizuku isn't running, shares still work; they open full screen.

## Setup

1. Install and start Shizuku (Wireless debugging). thedjchi's fork can restart itself after reboots.
2. Install PopShare, open it, tap **Allow** under Shizuku.
3. Star the apps you share to most. They get their own entries in the share sheet.
   On Samsung these appear in the direct-share row; make sure Direct share is enabled in
   Settings › Advanced features.

In the picker: tap opens in a pop-up, long-press opens full screen.

## Known limits

- Multi-file shares use the trampoline route; some apps may open their next screen full screen.
- A target that is already open full screen may come forward full screen instead of in a pop-up.
- Apps inside a work profile (e.g. Island) aren't listed.
- Shared files are copied; very large videos take a moment. Copies are deleted after a day.

## Building

`./gradlew assembleRelease`. CI builds on every push and publishes a GitHub release for
`v*` tags. For a stable signing key, set these repository secrets:
`POPSHARE_KEYSTORE_BASE64`, `POPSHARE_KEYSTORE_PASSWORD`, `POPSHARE_KEY_ALIAS`,
`POPSHARE_KEY_PASSWORD`. Without them the APK is signed with a throwaway debug key.

## License

MIT
