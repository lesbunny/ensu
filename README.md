# Ensu for Android

An Android-only source repository for Ensu, built from the Android application and its required shared Rust workspace.

## Project layout

- `android/` — Android Gradle project, Ensu app, and the shared Android components/fonts modules.
- `rust/` — Ensu's UniFFI bindings, Rust implementation, shared Rust crates, and code-generation tool.
- `LICENSE` — upstream project license.

Other Ente applications and platform projects (iOS, web, desktop, CLI, and unrelated mobile apps) are not included.

## Build

Requirements:

- Android Studio or Android SDK
- JDK 17
- Android NDK `27.3.13750724`
- Rust toolchain and the Android Rust targets
- CMake and the native build tools required by the Rust dependencies

Open the `android/` directory in Android Studio, or build from a terminal:

```sh
cd android
./gradlew :ensu:app:assembleDebug
```

Install on a connected Android device:

```sh
./gradlew :ensu:app:installDebug
```

The Rust JNI library is built by Gradle. The Android app's own README is at [android/apps/ensu/README.md](android/apps/ensu/README.md).

## Upstream

This is a curated Android-only source layout derived from [ente-io/ente](https://github.com/ente-io/ente). Ensu's application code and shared dependencies remain under their original license. See [LICENSE](LICENSE).
