# Current build configuration

This file supersedes the initial README's JDK 17 / SDK 35 / Gradle 8.11.1 build instructions.

- Build JVM: Oracle JDK 27, provisioned by actions/setup-java v5. Review Oracle's JDK license for your intended use.
- Gradle: 9.8.2.
- Android Gradle Plugin: 9.4.0.
- Android compile and target SDK: 37.
- Minimum Android SDK: 29.
- Java app source/bytecode compatibility: 17, deliberately distinct from the build JVM.
- SDK build-tools version: selected by AGP, not independently hard-coded.
- GitHub actions: checkout v6, setup-java v5, setup-gradle v6, upload-artifact v7.

Versions are pinned for reproducibility, not dynamically set to latest. This is a proposed modernized configuration, not proof of a successful build or complete third-party compatibility with JDK 27. Inspect the CI run before installing. No automatic fallback to an older JDK is configured.

Local build: install JDK 27, Gradle 9.8.2, Android SDK command-line tools, and platform android-37. Set ANDROID_HOME or sdk.dir in local.properties. Run `gradle --no-daemon --stacktrace :app:assembleDebug`. A Gradle wrapper is not yet included.

The app remains a diagnostic echo/voice/overlay shell. Qwen, 3D avatar, persistent memory, and device automation are not implemented.
