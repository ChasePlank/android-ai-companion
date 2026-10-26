# Filament release dependency source

Maven resolution for the three Filament 1.78.1 coordinates failed in CI. The build now fetches official Android AAR assets from https://github.com/google/filament/releases/tag/v1.78.1 instead. Exact filenames and SHA-256 digests, obtained from the GitHub release metadata, are pinned in tools/fetch_filament.py. Mismatches stop the build.

Run `python3 tools/fetch_filament.py` before a local Gradle build. CI runs it automatically. Downloaded binaries are build inputs under vendor/filament, not source files committed by this patch. Do not commit downloaded AARs.

Local AAR dependencies do not carry Maven transitive dependencies, so Kotlin stdlib 2.4.0, coroutines core/android 1.11.0 and AndroidX annotations 1.10.0 are declared explicitly, matching the release's Android source dependency versions. Their resolution and compiler compatibility are still subject to CI verification. The script prints embedded AAR requirements; no minCompileSdk, Kotlin metadata, or other compatibility checks are suppressed.

This changes artifact acquisition, not the avatar rendering code, model, Android SDK 36, JDK 27, or Gradle 9.8.2. A successful build and device render are not claimed at commit time.
