# Release build plan

Status: **planned, not started** (written 2026-10-14). Roadmap item 3 in
[`PROJECT_NOTES.md`](PROJECT_NOTES.md#roadmap). Answer the [open questions](#open-questions-for-the-user)
with the user before starting.

## Why

The user installs a **debug build**: unoptimized code, debugging enabled, signed with the
throwaway debug key generated on the VPS (`~/.android/debug.keystore`). A **release build** is
the version meant for real use: shrunk and optimized code, `debuggable=false`, signed with a
key of our own.

## What changes

### 1. Code shrinking (R8)

- `android/app/build.gradle.kts`: `buildTypes.release { isMinifyEnabled = true; isShrinkResources = true;
  proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }`.
- R8 removes unused code from the app and its libraries (Compose, Kotlin stdlib, AndroidX, ML Kit's
  Java side), renames and optimizes the rest.
- **Risk: reflection and JNI.** R8 can't see code that is only called by name or from native code:
  - ONNX Runtime: its native library calls back into `ai.onnxruntime.*` Java classes → keep rule
    `-keep class ai.onnxruntime.** { *; }` (check the onnxruntime-android AAR for bundled consumer
    rules first; add only what's missing).
  - ML Kit / Play services components are registered and loaded by name; their AARs normally ship
    consumer rules — verify at runtime rather than assume.
  - kotlinx-serialization is only used by `core`'s desktop CLI and tests, not the app.
- Symptom of a missing rule: crash or `ClassNotFoundException` / `NoSuchMethodError` at the first
  scan with that engine. Keep `app/build/outputs/mapping/release/mapping.txt` for each published
  build to de-obfuscate stack traces.

### 2. Own signing key

- Android installs an update only if it's signed with the **same key** as the installed app.
- Create a release keystore once:
  `keytool -genkeypair -v -keystore ~/keys/wine-label-scanner-release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000`
- **Never in the repo** (it's public). Keystore path and passwords in a local, gitignored
  `android/keystore.properties` (or environment variables), read by `signingConfigs.release`.
- **Never lose it**: without it no future version installs as an update (users must uninstall
  and reinstall). The user keeps a backup copy off the VPS.
- Switching from the debug-signed app to the release-signed one needs **one uninstall** on the
  phone (`adb uninstall winelabel.app`); only the saved engine choice is lost.

### 3. Keep debug builds for development

- `DeviceScanTest` and `tools/device-test.sh` use the debug build; keep using it day to day.
- Release build: `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
- To test R8 on the phone, run the instrumentation test against a minified build: a separate
  build type (e.g. `debugMinified { initWith(debug); isMinifyEnabled = true; proguardFiles(...) }`)
  or `testBuildType = "release"` with the release signing config.

## Expected effect

APK size (measured 74 MB debug):

| Part | Now | After R8 |
|---|---|---|
| OCR models (`assets/models`) | 32 MB | 32 MB |
| ONNX Runtime native library (arm64) | 18 MB | 18 MB |
| ML Kit (native library + model) | 12 MB | 12 MB |
| Dex (app + library code) | ~10 MB | ~1–2 MB |
| **Total** | **~74 MB** | **~64 MB** |

R8 only shrinks Java/Kotlin code. Other effects: smoother UI (Compose is much slower in debug
builds); **scan speed unchanged** (the time is spent in ONNX Runtime's native code).

## Bigger size reductions (separate projects, not part of this step)

| Option | Saves | Cost / risk |
|---|---|---|
| int8-quantized PP-OCR models (ONNX Runtime quantization tools) | 32 → ~10 MB, probably faster | Accuracy must be re-measured on both photo sets (`:core:cli eval`); rec model is the big one (21 MB) |
| Minimal ONNX Runtime build with only the operators our 3 models use | 18 → ~5 MB | Building ONNX Runtime for Android from source; maintenance on every ORT update |
| Drop ML Kit | 12 MB + its INTERNET/ACCESS_NETWORK_STATE permissions | The user decided to keep it (2026-10-14) |

## Steps (about an hour)

1. Enable R8 for release; add keep rules (start minimal, add what fails).
2. Create the keystore (after the user agrees where it lives); `signingConfigs.release` reading
   `android/keystore.properties`; add that file to `.gitignore`.
3. Build `assembleRelease`; check size (`ls -l`, `apkanalyzer` in the SDK's cmdline-tools).
4. On the phone over the adb tunnel: uninstall the debug app, install the release APK; run the
   on-device test against a minified build for **both engines** on both photo sets — results must
   equal the debug build (89/108, 31/37 for PP-OCR); check `adb logcat WineLabel:E AndroidRuntime:E`.
5. Ask before opening the app UI on the user's phone (see AGENTS.md); have the user try Take photo /
   Choose photo / Share into the app with the release build.
6. Document in AGENTS.md (commands) and PROJECT_NOTES (timeline, decisions, where the key is,
   how to build a release); keep each published build's `mapping.txt`.

## Open questions for the user

1. **Keystore location**: OK to keep it on the VPS (e.g. `~/keys/`), with the user downloading a
   backup copy (`scp <vps>:~/keys/wine-label-scanner-release.jks .`)?
2. **One-time uninstall** of the current debug-signed app when switching: OK?
3. **Distribution**: keep scp + `adb install`, or publish APKs as **GitHub Releases** of the public
   repo (downloadable directly on the phone, but by anyone)? Releases are created on github.com
   (no `gh` CLI on the VPS) or via the GitHub API with a token.
