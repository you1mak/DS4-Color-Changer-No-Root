# DS4 Light — No Root

Android 12+ app for controlling a DualShock 4 RGB light through Android's public input-device lighting APIs.

## No Root
This project does not use root, `su`, shell commands, Shizuku, sysfs, or privileged device access.

## How it works
The app locates a DualShock 4 exposed as an Android `InputDevice`, finds its RGB `Light`, opens a `LightsSession`, and keeps that session alive inside a foreground `connectedDevice` service. The selected RGB color is then requested through `LightsRequest`.

## Build
The included GitHub Actions workflow builds a debug APK with AGP 9.4.0, Gradle 9.6.0, Java 17, compileSdk 36, and Build Tools 36.0.0.

## Important
The foreground session is intentionally closed when the service is stopped or killed. Android may still stop/restrict a foreground service under system-level conditions. A force-stop of the app always stops it.
