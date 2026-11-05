# TV Parental Control App

## Product context

- Target device: Thunderbird (LeiNiao) Android TV, Android 9/API 28.
- ADB endpoint: `10.0.0.44:5555`.
- The app presents multiple-choice questions in a large centered floating window (about 86% x 82% of the screen, dimmed background) above the current TV app. D-pad moves the selection and OK submits it.
- A parent sets a 6-key D-pad unlock password (up/down/left/right) on the launcher; it is stored only as a salted SHA-256 hash. On the wrong-answer lock screen the password ends the lockout early; 5 wrong attempts trigger a 30-second cooldown. The daily-quota lock cannot be unlocked by password.
- Parents manage the question bank and usage rules through a Web admin page, initially hosted on a local computer and later deployable to Alibaba Cloud ECS.

## Development rules

- Keep all Android app interface text in English. Keep the backend admin interface and its user-facing messages in Chinese unless the user explicitly requests another language.
- Use Kotlin and Android Studio for the Android app. Keep Android dependencies minimal and support API 28.
- Keep the launcher and overlay usable with a TV remote: large labels, visible focus, D-pad navigation, and OK activation.
- Global display requires `SYSTEM_ALERT_WINDOW`; request it through Android settings and never try to grant it silently.
- Keep package name `com.example.tvparental` unless the user explicitly changes it.
- Unlock challenges require five consecutive correct answers. A wrong answer resets challenge progress, starts the configured lockout, and restarts the five-question challenge when the lockout ends; the parent PIN remains an override.
- The daily-use counter estimates screen-on time, persists locally, resets by local calendar day, and must not count time during an answer lockout.
- Use a foreground service for usage tracking. Re-arm question alarms after `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED`; keep alarm bookkeeping available during direct boot.
- Randomly select from the 50 built-in questions, avoid repeating the previous question, and preserve an unanswered question across service restarts.
- An answer is consumed once. A wrong answer starts the configured lockout; a daily quota exhaustion blocks until the next local day.
- The default rules are 60 minutes per day, one question every 10 minutes, a 10-minute wrong-answer lock, and a 5-minute sync interval (configurable 1-60). The remote backend can change these values.
- Daily usage rules support per-weekday allowed time windows and disabled time windows. Disabled windows always take priority when they overlap allowed windows.
- The device polls the backend: full sync on service start, then every `syncIntervalMinutes` from the synced settings.
- The device syncs questions and rules from `https://tvparental.mediainsight360.com/api/v1/device/state` without TV-side configuration. Management requires a username/password session; never put admin credentials in the Android app.
- Backend code uses Python's standard library. Keep runtime state and secrets outside version control. Use HTTPS for any public ECS deployment.
- Check whether the TV is reachable before a device test. This TCL firmware rejects `adb install -r`; use TCL MultiScreen Interaction and confirm the install on the TV. Follow `docs/INSTALL_TCL.md`.

## Current implementation

- Android sources are under `app/`; the 50-question seed bank is `app/src/main/assets/questions.json`.
- The launcher displays today's use and supports manual sync and an immediate test question. The backend URL is fixed in `ControlState.BACKEND_URL`.
- The Web admin has separate `/settings` and `/questions` pages and a modal question form. Configure/reset the single admin with `python backend/server.py --set-admin`; password hashes persist in `backend/data/admin.json`.
- The question bank supports DeepSeek question generation: poetry questions use a poem title, while math, geography/history, and science questions are generated randomly by category. Preview and selected batch import are supported. `DEEPSEEK_API_KEY` stays in the server environment; the standard-library client in `backend/deepseek.py` uses `deepseek-flash` and JSON output. Generation never writes the bank; batch import validates every item and skips duplicate prompts.
- The Web backend and admin page are under `backend/`; run its API tests with `python -m unittest discover -s backend/tests -v`.
- ECS deployment uses host-level Caddy + a `tvparental` systemd service running as dedicated user `tvparental-deploy`, code at `/var/www/html/tvparental`. `backend/compose.yaml` remains as a Docker alternative. Keep `backend/.env` and `backend/data/` private.
- `.github/workflows/deploy-ecs.yml` auto-deploys backend changes on `main` via rsync + SSH + `systemctl restart tvparental`; required secrets are documented in `docs/DEPLOY_ECS.md`.
- Windows local Android builds use `gradlew.bat assembleDebug`; Gradle Wrapper is pinned to 9.1.0.
- TCL MultiScreen Interaction installation and the floating overlay over the launcher were verified previously. The launcher opens the overlay permission page on first launch (Android offers no install-time prompt); the app never grants it silently. The API 28 Android TV emulator has no overlay settings page, so grant it with `appops` there. Video-app overlay behavior and reboot recovery should be rechecked after significant Android changes.
