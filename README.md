# TVStudio

Clean up an Android TV from your Android phone. There's no root, no computer and nothing to uninstall.

> **Status: early, and not yet tested on a real TV.** Use it at your own risk (see [Risks](#risks)).

## What it does

1. Connects to the TV over your Wi-Fi using Android's debug connection (ADB).
2. Reads the TV's model and finds a tested list of apps to disable, or scans the TV if there's no list.
3. Shows you a checklist. Streaming apps, voice, casting and updates are never ticked for you.
4. Saves each app's current state, then disables what you chose with `pm disable-user`.
5. Lets you undo everything with one tap.

## What you need

- An Android phone (Android 8.0 or newer) on the same Wi-Fi as the TV.
- An Android TV or Google TV with **USB debugging** switched on in Developer options. An app can't turn
  this on for you, so the app walks you through it.

## What it won't do yet

- **Other TV systems:** Samsung, LG, Roku and Vizio TVs have no ADB, so they can't work.
- **Home screen ads:** they're often part of the TV's own launcher, which this version never disables.
- **Pairing-code connections:** some Google TVs only offer Wireless debugging with a pairing code.
- **iPhone:** there's no iPhone version.
- **Android older than 7 on the TV:** the connection library needs a newer shell protocol.

## Safety

These rules are in the engine ([`core`](core/src/main/kotlin/com/tvstudio/core)) and have no override:

- Refuses core Android and TV system packages, and what the TV needs: Play Store, Play Services,
  keyboard, remote and Bluetooth pairing, network.
- Asks the TV which apps provide Home, its HDMI inputs, Settings and its keyboard, then keeps them.
- Keeps anything that may drive the HDMI, AV or tuner inputs, and anything it can't check.
- Never disables stock launchers or Google TV's setup wizard, which can own the saved Wi-Fi network.
- Saves the restore record before the first change. A retry never overwrites the original state.
- Reads the TV back after every batch rather than trusting its answers, and stops if the TV refuses
  everything or Home changes.
- Starts no apps on the TV, so it can't open a factory menu by accident.

## Risks

Disabling apps can still break things, despite these checks. CleanYourTV's history includes TVs that stopped
booting or lost their inputs. A TV that won't boot can't be undone from the phone, and may need a factory reset
or repair. The model lists come from community reports on specific firmware, and aren't checked on your TV.

While debugging is on, the TV's port 5555 is unencrypted and open on your network. Turn it off when you finish;
the app's last step checks that it closed.

## Install

Open the repository's **Actions** tab, choose the latest **Build TVStudio** run and download the `TVStudio-debug`
artifact. Tagged versions appear under **Releases**. Your phone will ask you to allow installing from outside the
Play Store.

Debug builds are signed with a temporary key, so uninstall the old one before installing a newer one. The app
then gets a new ADB key, and the TV will ask you to allow it again.

## Development

| Path | What it is |
|---|---|
| `core/` | The engine and safety rules. Plain Kotlin, tested on a normal JVM with a fake TV |
| `adb/` | The real connection, using [dadb](https://github.com/mobile-dev-inc/dadb), plus the emulator test |
| `app/` | The Android app, built with Jetpack Compose |
| `tools/sync_lists.py` | Copies model lists from CleanYourTV at a pinned commit |

```bash
./gradlew :core:test :adb:test      # JDK 17
./gradlew :app:assembleDebug        # also needs the Android SDK
python -I tools/sync_lists.py       # refresh the model lists, then review the diff
```

CI runs the tests, builds the APK and runs the engine against an Android TV emulator over a real ADB connection.

### Signed releases

Pushing a tag like `v0.1.0` builds a signed release. It needs these repository secrets: `TVSTUDIO_KEYSTORE_BASE64`,
`TVSTUDIO_KEYSTORE_PASSWORD`, `TVSTUDIO_KEY_ALIAS` and `TVSTUDIO_KEY_PASSWORD`. Never commit a keystore.

## Credits

TVStudio is new code, built on the research in [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT). Its
model lists are bundled here, and its safety rules are reimplemented. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
