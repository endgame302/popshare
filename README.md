# PopShare

ColorOS-style sharing for other Android phones: the app you share to opens in a floating
pop-up window (Samsung pop-up view / Android freeform) instead of taking over the screen, and
you stay in the app you shared from.

Built and tested against One UI 8.5 (Android 16). Experimental.

## How it works

1. You share something and pick **Pop-up share** (or a starred app's own entry) in the share sheet.
2. PopShare copies any shared files into its own storage, so the target app keeps access to them.
3. Through [Shizuku](https://github.com/thedjchi/Shizuku), PopShare runs
   `am start --windowingMode 5` on a tiny invisible activity of its own. That creates a new
   pop-up window.
4. The invisible activity starts the chosen app *inside the same window* and disappears.

Because the last hop is made by PopShare itself, text, images, multiple files and file names
all arrive intact. The shell never handles your content.

If Shizuku isn't running, shares still work; they open full screen.

## Setup

1. Install and start Shizuku (Wireless debugging). thedjchi's fork can restart itself after reboots.
2. Install PopShare, open it, tap **Allow** under Shizuku.
3. Star the apps you share to most. They get their own entries in the share sheet.
   On Samsung these appear in the direct-share row; make sure Direct share is enabled in
   Settings › Advanced features.

In the picker: tap opens in a pop-up, long-press opens full screen.

## Known limits

- Apps whose share screen insists on its own task (`singleTask`/`singleInstance`) open full screen.
- Apps inside a work profile (e.g. Island) aren't listed.
- Shared files are copied; very large videos take a moment. Copies are deleted after a day.

## Building

`./gradlew assembleRelease`. CI builds on every push and publishes a GitHub release for
`v*` tags. For a stable signing key, set these repository secrets:
`POPSHARE_KEYSTORE_BASE64`, `POPSHARE_KEYSTORE_PASSWORD`, `POPSHARE_KEY_ALIAS`,
`POPSHARE_KEY_PASSWORD`. Without them the APK is signed with a throwaway debug key.

## License

MIT
