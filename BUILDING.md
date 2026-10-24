# Current build configuration

This file supersedes the original README toolchain instructions.

- Build JVM: Oracle JDK 27.
- Gradle: 9.8.2.
- Android Gradle Plugin: 9.4.0.
- Runner: ubuntu-26.04.
- Compile and target SDK: 36, an explicit compatibility fallback because SDK Manager failed to locate platforms;android-37 even with preview channels enabled. API 36 is not claimed to be the latest available SDK.
- Minimum SDK: 29.
- Java source/bytecode compatibility: 17, distinct from the build JVM.
- SDK build tools are selected by AGP.

The API 36 configuration is not build-verified at commit time. No other runtime/toolchain downgrade is included. Review Oracle JDK licensing for your intended use.

Local build: install JDK 27, Gradle 9.8.2, Android SDK command-line tools and platform android-36. Set ANDROID_HOME or sdk.dir in local.properties, then run `gradle --no-daemon --stacktrace :app:assembleDebug`. No Gradle wrapper is included yet.

The app remains a diagnostic echo/voice/overlay shell, without Qwen, a 3D avatar, persistent memory, or device automation.
