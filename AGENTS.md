# Repository workflow

- When the user requests a GitHub update, publish an installable Android APK for the same source commit as part of that update. Include the Android version in its filename, such as `PlasmaWave-0.3.apk`. Use `scripts/publish-apk.sh` and keep the download link in the README working.
- Ask the user before running tests. Do not create backups or publish changes unless the user has requested or approved them.
