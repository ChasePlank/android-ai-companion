# Filament release dependency source

The build pins official Filament 1.75.0 Android AARs from https://github.com/google/filament/releases/tag/v1.75.0. Filament 1.78.1 release AARs required API 37, whose numeric SDK package was unavailable in the runner catalog across all channels. The app and CI therefore return to API 36 rather than bypassing AAR metadata checks.

The tagged 1.75.0 Android build declares compileSdk 34, Kotlin 2.0.21, coroutines 1.9.0 and annotations 1.9.0. This app retains its explicitly aligned Kotlin compiler and stdlib 2.4.0, and uses the release's coroutines and annotation versions. Runtime and renderer compatibility still require testing.

Run `python3 tools/fetch_filament.py` before local Gradle builds; CI runs it automatically. The script verifies pinned SHA-256 digests from GitHub release metadata, checks classes.jar, reports embedded AAR requirements, and rejects minCompileSdk values above 36. Gradle's own metadata checks remain enabled. Downloads stay under vendor/filament and should not be committed.

This patch does not change minSdk 29, targetSdk 36, JDK 27, Gradle 9.8.2, renderer source or the GLB model. Successful compilation and rendering are not claimed before verification.
