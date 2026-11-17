# ZTE Family Toolbox

Android USB-OTG ADB utility for ZTE, Nubia, RedMagic and other Android devices that expose the standard Android Debug Bridge USB interface.

## Current functionality

- USB host device scan and Android USB permission request.
- Detection of the standard ADB interface and its bulk endpoints.
- ADB CNXN/AUTH handshake with an app-local RSA keypair; the target phone must accept the normal Android USB-debugging authorization prompt.
- ADB shell command console with output, clear and copy controls.
- Device property summary (model, manufacturer, Android version, build fingerprint, platform, serial, API level and security patch when exposed).
- Read-only diagnostics: battery, memory, storage, CPU, build properties, ADB setting, network, packages and recent logcat output where Android permits it.
- Read-only installed package listing and version-detail shortcuts.
- Confirmation-gated reboot, recovery and bootloader restart requests.
- Share/export of collected console diagnostic output.
- System, light and dark appearance choices.
- Searchable ZTE/Nubia/RedMagic reference model table. Entries are reference data, not a complete official device catalogue.
- GitHub Actions manual, main-branch and version-tag build triggers.

## Requirements and connection steps

1. Install the debug APK on an Android phone/tablet with USB host/OTG support.
2. On the target ZTE/Nubia/RedMagic phone, enable Developer options and USB debugging.
3. Connect the target to the host using a data-capable OTG adapter/cable.
4. Unlock the target, open this app, choose **Devices**, and tap **Scan / Refresh**.
5. Select **Connect via ADB** and grant the Android USB permission. Accept the target phone's **Allow USB debugging** RSA fingerprint prompt.
6. Use the ADB Console and Diagnostics screens.

A cable, USB mode, OEM implementation or Android policy that does not expose the standard ADB USB interface cannot be supported by this USB-ADB transport. Fastboot mode is not an ADB shell and is not detected as an ADB device.

## Security and limitations

- The app does not bypass USB debugging authorization or Android permissions.
- The ADB keypair is stored in the app's private internal files directory. Do not share the app's private data backup.
- Shell commands run with the privileges of the target device's `shell` user, not root. Root-only data and restricted logs may be unavailable.
- Reboot shortcuts, flashing, partition modification, bootloader unlocking, EDL exploitation and FRP bypass are intentionally not included.
- Battery health, chipset, serial, screen metrics and other properties vary by OEM and Android version; unavailable values should be treated as unknown rather than inferred.
- Restart requests are sent through the target shell account and may be denied by the OEM build; a successful ADB stream open is not a guarantee that a restart completed.
- This is not a certified or security-audited ADB implementation. Validate on a spare device before relying on it.

## Build locally

Open the project in Android Studio with Android SDK Platform 34 and JDK 17 installed. The project uses Android Gradle Plugin 8.5.2, Kotlin 1.9.24, Gradle 8.7, Compose Compiler 1.5.14 and `minSdk 26` (Android 8.0).

```bash
gradle :app:assembleDebug
gradle :app:assembleRelease
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. The release build is unsigned unless a signing configuration is supplied. For GitHub Actions signing, add repository secrets named `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`; the workflow decodes the keystore into the runner temporary directory and signs the release build.

## GitHub Actions

`.github/workflows/build-apk.yml` supports:

- Manual run from **Actions → Android APK Build → Run workflow**.
- Pushes to `main`.
- Version tags matching `v*`.

The workflow builds the debug APK and attempts a release APK, then uploads artifacts named `ZTE-Family-Toolbox-debug-APK` and, when available, `ZTE-Family-Toolbox-release-APK`.
