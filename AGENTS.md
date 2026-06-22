# AGENTS.md

## Cursor Cloud specific instructions

### Project overview

Manga Translator (MT阅读器) — an Android app that translates manga/comics using on-device ML models (ONNX) and LLM APIs. Single Gradle module (`:app`), Kotlin 2.0, AGP 8.9.2.

### Environment requirements

- **JDK 17** (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`). The VM has JDK 21 by default; you must ensure JDK 17 is on `PATH` before JDK 21.
- **Android SDK** at `$HOME/android-sdk` with `platforms;android-36`, `build-tools;35.0.0`, and `platform-tools`.
- Environment variables (`JAVA_HOME`, `ANDROID_HOME`, `PATH`) are persisted in `~/.bashrc`.

### Build / lint / test commands

See `README.md` § "从源码构建" for details. Quick reference:

| Task | Command |
|------|---------|
| Debug build | `./gradlew :app:assembleDebug` |
| Release build | `./gradlew :app:assembleRelease` |
| Lint | `./gradlew :app:lint` |
| Unit tests | `./gradlew :app:testDebugUnitTest` |

### Non-obvious caveats

- **No unit/instrumentation tests exist** in the repo (test source sets are empty). `testDebugUnitTest` will succeed with `NO-SOURCE`.
- **No KVM** in Cloud Agent VMs, so the Android emulator cannot run. The "hello world" verification for this project is a successful debug APK build + `aapt dump badging` validation.
- **ONNX model files** (listed in README) are required at runtime but are **not** in the repo (gitignored). They are not needed to compile.
- **Signing keystore** (`keystore.jks`) credentials are in `gradle.properties`; the file is absent in the repo, but debug builds do not require it.
- The first Gradle build downloads the Gradle 8.11.1 distribution and all Maven dependencies, which takes ~2 minutes. Subsequent builds are incremental (~5 s).
- APK output: `app/build/outputs/apk/debug/app-debug.apk` (~111 MB, includes ONNX runtime native libs).
