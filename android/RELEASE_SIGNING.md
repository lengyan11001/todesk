# Android Release Signing

Android overwrite upgrades only work when every APK uses the same `applicationId` and the same signing key.

Current package:

- `applicationId`: `top.bhzn.todesk`
- `versionCode`: `6`
- `versionName`: `0.1.8`

Build a signed release APK:

```powershell
$env:BHZN_ANDROID_STORE_PASS = "replace-with-private-password"
$env:BHZN_ANDROID_KEY_PASS = "replace-with-private-password"
.\build-apk.ps1 -Channel release -VersionCode 9 -VersionName 0.1.8
```

The release keystore is created at `android\keystore\bhzn-todesk-release.keystore` by default. Keep that file backed up privately. It is ignored by git.

Upgrade rule:

- Increase `versionCode` on every public release.
- Keep `applicationId` unchanged.
- Keep using the same release keystore and alias.
- Do not install a debug-signed APK over a release-signed APK.
