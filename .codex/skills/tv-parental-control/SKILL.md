---
name: tv-parental-control
description: Build, test, and install the Kotlin Android TV parental-control app across Windows development environments.
---

# TV Parental Control

Use this skill for Android or backend work in this repository. The target is a Thunderbird/TCL Android 9 TV, normally at `10.0.0.44:5555`. The Android app uses Kotlin framework views and TV remote navigation; a Python standard-library service provides the parent Web admin page and device sync API. The repository documentation and scripts are the durable source of environment setup; do not depend on chat memory or hard-code a developer's SDK/JDK path.

## Session bootstrap and build environment

- At the start of Android work, read repository-root `AGENTS.md` and `README_BUILD.md`, then inspect the relevant source and script. Read `docs/INSTALL_TCL.md` before TV installation.
- On Windows, run `build.bat` from the repository root. It discovers Java from `JAVA_HOME`, Android Studio/JBR, JDK environment variables, or PATH. It reuses an extracted Gradle 9.1.0 distribution from local caches when available, otherwise the committed `gradlew.bat` downloads the pinned distribution. Never require a global Gradle installation.
- For a fresh clone, Android Studio should generate the ignored, machine-specific `local.properties`. Install Android SDK Platform 37 and Platform-Tools through SDK Manager. `.tools/`, `.gradle/`, `build/`, and `local.properties` are local machine state; never assume or copy their paths between computers.
- Install to an emulator with `install_emulator.bat`; it discovers ADB using `ANDROID_SDK_ROOT`, `ANDROID_HOME`, `local.properties`, the standard Windows SDK directory, or PATH. Install to the TCL TV with `install_device.bat`; it discovers ADB using the SDK environment variables, `local.properties`, or PATH.
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
- Parents can add/delete questions and change usage rules in the Web admin page. Device sync reads `/api/v1/device/state`; admin access uses a username/password session. Never put admin credentials in the Android app.
- The SQLite database initializes the single admin account as `admin/admin`; the admin can change its password in the Web UI, and `python backend/server.py --set-admin` resets it. Store only salted password hashes in the SQLite database.
- The authenticated admin `/records` page displays per-day device usage and answer history.
- The launcher supports setting the backend URL, manual sync, a usage summary, permission access, and an immediate test question.

## Device workflow

1. Check whether the TV is reachable before a device test. Its current address is `10.0.0.44:5555`.
2. This TCL firmware rejects `adb install -r`; use TCL MultiScreen Interaction and confirm installs/upgrades on the TV. Follow `docs/INSTALL_TCL.md`.
3. Verify `SYSTEM_ALERT_WINDOW`, `APP_AUTO_START`, and `APP_ASSOC_START` app-ops after installation. Android overlay permission cannot be silently granted by the app.
4. Verify the 10-minute timer, daily usage display and reporting, D-pad/OK answer flow, 4-of-5 challenge result, wrong-answer countdown, overlay above another app, and startup after a full reboot.
5. Distinguish code/build verification from installation on a physical TV; report device behavior as unverified unless the device was reached and tested in the current task.

## Implementation constraints

- Keep `minSdk` at 28 and declare `LEANBACK_LAUNCHER` plus normal `LAUNCHER` entry points.
- Use a foreground service and `TYPE_APPLICATION_OVERLAY` for the global full-screen question and lock screen.
- Every interactive control must be reachable with D-pad arrows and activated by OK/Enter.
- Restore question alarms and usage tracking after `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED`.
- Backend uses Python's standard library. Keep `backend/.env` and `backend/data/` out of version control. Use the included Caddy HTTPS proxy for ECS; do not expose the app's port 8080 directly to the public internet.
- Run backend tests with `python -m unittest discover -s backend/tests -v` and Windows Android builds with `build.bat`.
