# Persistent-signing update test

The user reported successful installation of the verified APK and successful replacement with the same APK using adb install -r.

Next test: build a newer APK using the existing persistent signing secret, verify the signing certificate and APK SHA-256 in the build summary, then install over the current app without uninstalling.

Do not rotate or regenerate the signing key. A same-APK reinstall does not yet prove compatibility across separate builds.
