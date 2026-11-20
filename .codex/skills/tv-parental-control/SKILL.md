---
name: tv-parental-control
description: Build, test, and install the Kotlin Android TV parental-control app across Windows development environments.
---

# TV Parental Control

Use this skill for Android or backend work in this repository. The target is a Thunderbird/TCL Android 9 TV, normally at `10.0.0.44:5555`. The Android app uses Kotlin framework views and TV remote navigation; a Python standard-library service provides the parent Web admin page and device sync API. The repository documentation and scripts are the durable source of environment setup; do not depend on chat memory or hard-code a developer's SDK/JDK path.

## Session bootstrap and build environment

- At the start of Android work, read repository-root `AGENTS.md` and `docs/README_BUILD.md`, then inspect the relevant source and script. Read `docs/INSTALL_TCL.md` before TV installation.
- On Windows, run `scripts/build.bat` from the repository root. It discovers Java from `JAVA_HOME`, Android Studio/JBR, JDK environment variables, or PATH. It reuses an extracted Gradle 9.1.0 distribution from local caches when available, otherwise the committed `gradlew.bat` downloads the pinned distribution. Never require a global Gradle installation.
- For a fresh clone, Android Studio should generate the ignored, machine-specific `local.properties`. Install Android SDK Platform 37 and Platform-Tools through SDK Manager. `.tools/`, `.gradle/`, `build/`, and `local.properties` are local machine state; never assume or copy their paths between computers.
- Install to an emulator with `scripts/install_emulator.bat`; it discovers ADB using `ANDROID_SDK_ROOT`, `ANDROID_HOME`, `local.properties`, the standard Windows SDK directory, or PATH and uses `adb install -r` to preserve settings and binding credentials. Install to the TCL TV with `scripts/install_device.bat`; it discovers ADB using the SDK environment variables, `local.properties`, or PATH.
- For build failures, inspect the actual JDK, SDK, Gradle wrapper/cache, and script output before concluding a tool is missing. Do not infer failure from `java` or `gradle` not being globally available when the project script can locate them.

## Change scope

- This app is not formally released. New changes do not need backward compatibility or migration paths; use the simplest implementation that meets the current requirements, and do not preserve old behavior solely for compatibility.
- Keep app `versionName` and backend version in the root `VERSION` file. Increment Android `versionCode` separately for each installable APK, and include the shared version in the APK filename.

## Product behavior

- Default rules: 60 minutes of screen-on time per local day, one question every 10 minutes, and a 10-minute lock after a challenge scores below four correct answers out of five.
- The app includes 50 built-in middle-school questions. It chooses randomly without repeating the last question and preserves an unanswered question across service restarts.
- Each challenge question allows one submission. A score below four correct answers out of five blocks the TV overlay until the lock timer expires; exhausting the daily quota blocks until the next local day.
- The unlock challenge asks five questions and passes with at least four correct. A score below four starts the configured lockout, then restarts all five questions.
- Every submitted answer (correct or incorrect) is queued locally until reported. The device reports cumulative daily screen-on usage to the backend once per minute.
- Device records use `Settings.Secure.ANDROID_ID` as the stable device ID; emulator names come from `ro.kernel.qemu.avd_name`, with manufacturer/model as the physical-device fallback.
- Parents register independent accounts and manage their own questions, rules, devices and records. The TV shows a server-issued six-digit code valid for five minutes; Web admin previews the device and claims it. A device cannot change owners until the previous owner unbinds it. Device sync/reporting requires its random credential, device ID and binding ID; never use a device ID alone as authentication or put account passwords in Android.
- Reinstalling an unbound TV can replace its pending credentials and code. Already-bound devices require owner unbinding, returned as HTTP 409 with `errorCode: device_already_bound`; never treat a generic 401 as proof of ownership. Advanced settings use full-row focusable ON/OFF cards, Up/Down navigation, OK toggling and a fixed Done action. The app has no accessibility authorization button; shortcut blocking is authorized in system settings.
- The main SQLite database stores salted account password hashes, device credential hashes and ownership, with the original admin's state unchanged. Other accounts use sibling `<database-name>.accounts/<id>.sqlite3` files. Back up all of these together. The original account defaults to `admin/admin`; `python backend/server.py --set-admin` resets an existing username's password. Anonymous device endpoints are closed; deployment health checks use `/api/v1/health`.
- AI question generation supports poem titles, random math/geography/history/science questions, and English questions based on a single English word. Generated questions are previewed before selected items are imported into the bank.
- The authenticated admin `/records` page displays per-day device usage and answer history.
- The launcher shows usage and permission status, with four actions: parent PIN, manual sync, advanced settings and Chinese account binding. The binding dialog automatically gets/renews its code, polls for completion and has only a Close button. Overlay/media permissions use documented ADB commands; there are no permission buttons, automatic settings redirects or test-question entry.

## Device workflow

1. Check whether the TV is reachable before a device test. Its current address is `10.0.0.44:5555`.
2. This TCL firmware rejects `adb install -r`; use TCL MultiScreen Interaction and confirm installs/upgrades on the TV. Follow `docs/INSTALL_TCL.md`.
3. Verify `SYSTEM_ALERT_WINDOW`, `APP_AUTO_START`, and `APP_ASSOC_START` app-ops after installation. Android overlay permission cannot be silently granted by the app.
4. Verify the 10-minute timer, daily usage display and reporting, D-pad/OK answer flow, 4-of-5 challenge result, wrong-answer countdown, overlay above another app, and startup after a full reboot.
5. Distinguish code/build verification from installation on a physical TV; report device behavior as unverified unless the device was reached and tested in the current task.

## Implementation constraints

- Android app text remains English except the account-binding entry, dialog and binding-specific messages, which are Chinese at the user's request.
- Keep `minSdk` at 28 and declare `LEANBACK_LAUNCHER` plus normal `LAUNCHER` entry points.
- Use a foreground service and `TYPE_APPLICATION_OVERLAY` for the global full-screen question and lock screen.
- Every interactive control must be reachable with D-pad arrows and activated by OK/Enter.
- Restore question alarms and usage tracking after `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED`.
- Backend uses Python's standard library. Keep `backend/.env` and `backend/data/` out of version control. ECS deployment uses the host's Caddy and a systemd service. Dockerfile and Compose are not used; do not recreate them. Do not expose the app's port 8080 directly to the public internet.
- Run backend tests with `python -m unittest discover -s backend/tests -v` and Windows Android builds with `scripts/build.bat`.
