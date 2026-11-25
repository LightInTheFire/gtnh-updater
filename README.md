# GTNH Updater

GTNH Updater is a Kotlin/Native command-line utility for GT New Horizons. The
current executable prints a startup message. Update logic is planned.

## Development

Install JDK 25. Open the project root in
IntelliJ IDEA as a Gradle project and select JDK 25 as the Gradle JVM. The
Gradle Wrapper uses Gradle 9.7.0 and the build uses Kotlin 2.4.21. Internet
access is required on first build to download Gradle and the Kotlin/Native
toolchain. On macOS, install the Xcode command-line tools (`xcode-select --install`).

## Build and run

Run the command for your build host from the project root.

| Build host | Target | Command |
| --- | --- | --- |
| Linux x86-64 | Linux x86-64 | `./gradlew packageReleaseLinuxX64` |
| Linux x86-64 | Linux ARM64 (cross-compiled) | `./gradlew packageReleaseLinuxArm64` |
| macOS Intel | macOS Intel | `./gradlew packageReleaseMacosX64` |
| macOS Apple Silicon | macOS Apple Silicon | `./gradlew packageReleaseMacosArm64` |
| Windows x86-64 | Windows x86-64 | `.\gradlew.bat packageReleaseMingwX64` |

Packaged executables are in `build/release/<target>/gtnh-updater` (with
`.exe` on Windows). For example, run `./build/release/linuxX64/gtnh-updater`
on Linux x86-64. Running a packaged executable requires no JVM.
