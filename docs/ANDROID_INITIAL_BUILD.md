# Original Android build

Base: f8f2de29e6ac1755f505fc00d444480267399269 (2026-09-27, root commit).

This branch preserves the original Android application sources and UI. Only the dedicated build workflow, signing helper and this note are added. The workflow verifies that app/ matches the root commit before building.

During the build only, the application ID becomes com.example.namufarm.original and the app label becomes 나무팜 초기버전, so it installs alongside the current app with a separate local database. The source UI is unchanged. Signing uses the existing repository release certificate. The APK is NamuFarm-original-release.apk in the workflow's draft release.

The initial application keeps its legacy Google Apps Script integration. It is not the current quote/inventory server client. This branch does not deploy or modify any operating server.
