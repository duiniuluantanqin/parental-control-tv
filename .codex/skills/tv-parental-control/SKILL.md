---
name: tv-parental-control
description: Build and test the Kotlin Android TV parental-control app for the Thunderbird Android 9 television.
---

# TV Parental Control

Use this skill for work in this repository. The target is a Thunderbird Android 9 TV with ADB at `10.0.0.44:5555`. The Android app uses Kotlin framework views and TV remote navigation; a small Python standard-library service provides the parent Web admin page and device sync API.

## Product behavior

- Default rules: 60 minutes of screen-on time per local day, one question every 10 minutes, and a 10-minute lock after an incorrect answer.
- The app includes 50 built-in middle-school questions. It chooses randomly without repeating the last question and preserves an unanswered question across service restarts.
- Each question allows one submission. A wrong answer blocks the TV overlay until the lock timer expires; exhausting the daily quota blocks until the next local day.
- Parents can add/delete questions and change usage rules in the Web admin page. Device sync reads `/api/v1/device/state`; admin writes use a bearer token. Never put the admin token in the Android app.
- The launcher supports setting the backend URL, manual sync, a usage summary, permission access, and an immediate test question.

## Device workflow

1. Check whether the TV is reachable before a device test. Its current address is `10.0.0.44:5555`.
2. This TCL firmware rejects `adb install -r`; use TCL MultiScreen Interaction and confirm installs/upgrades on the TV. Follow `docs/INSTALL_TCL.md`.
3. Verify `SYSTEM_ALERT_WINDOW`, `APP_AUTO_START`, and `APP_ASSOC_START` app-ops after installation. Android overlay permission cannot be silently granted by the app.
4. Verify the 10-minute timer, daily usage display, D-pad/OK answer flow, wrong-answer countdown, overlay above another app, and startup after a full reboot.
5. The TV was offline during the latest implementation session; do not assume that the current code has been installed or device-tested.

## Implementation constraints

- Keep `minSdk` at 28 and declare `LEANBACK_LAUNCHER` plus normal `LAUNCHER` entry points.
- Use a foreground service and `TYPE_APPLICATION_OVERLAY` for the global full-screen question and lock screen.
- Every interactive control must be reachable with D-pad arrows and activated by OK/Enter.
- Restore question alarms and usage tracking after `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED`.
- Backend uses Python's standard library. Keep `backend/.env` and `backend/data/` out of version control. Use the included Caddy HTTPS proxy for ECS; do not expose the app's port 8080 directly to the public internet.
- Run backend tests with `python -m unittest discover -s backend/tests -v` and Android builds with `gradlew.bat assembleDebug` on Windows.
