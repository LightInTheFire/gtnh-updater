# GTNH Updater

GTNH Updater is a Kotlin/Native terminal utility for GT New Horizons clients
in Prism Launcher and MultiMC.

## Development

Install JDK 25. Open the project root in
IntelliJ IDEA as a Gradle project and select JDK 25 as the Gradle JVM. The
Gradle Wrapper uses Gradle 9.7.0 and the build uses Kotlin 2.4.21. Internet
access is required on first build to download Gradle and the Kotlin/Native
toolchain. On macOS, install the Xcode command-line tools (`xcode-select --install`).

## Project structure

- `:core` contains the Kotlin/Native library for version catalogs, settings,
  downloads, instance preparation, and Prism integration. Its entry point is
  `ru.light.gtnh.updater.core.Updater`.
- The root module builds the executable. It contains the command-line interface
  and the Mordant TUI under `presentation/cli` and `presentation/tui`.

A desktop frontend can depend on `:core`. It can call the same suspend
operations for catalog refresh, downloads, and preparation. Download and
preparation report progress through callbacks. Run filesystem operations off
the UI thread and forward progress updates to the UI thread.

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

## Release archives

After all five builds pass, the **Native release binaries** workflow uploads
a `release-bundle` artifact with the platform archives and `SHA256SUMS`.
Version tags such as `v0.2.0` produce names such as
`gtnh-updater-0.2.0-linux-x64.tar.gz` and
`gtnh-updater-0.2.0-windows-x64.zip`. Branch and pull request builds use
`snapshot-<short-commit>` in place of the version.

The Linux and macOS archives contain an executable named `gtnh-updater`
with its execute permission preserved. The Windows ZIP contains
`gtnh-updater.exe`. Upload the five archives and `SHA256SUMS` from the
tag's bundle as individual GitHub Release assets.

## Diagnostic log

The terminal UI and command line write a local log to
`~/.gtnh-updater/updater.log` (`%USERPROFILE%/.gtnh-updater/updater.log` on
Windows). Attach it to a bug report after reproducing an issue. Entries
include UTC timestamps, a session identifier, the application version at
session start, operation outcomes, and exception types. The log excludes
exception messages, tokens, URLs, local paths, and configuration contents.
At 1 MiB logging pauses for the rest of the session. On the next launch
the file is rotated to `updater.previous.log`.

## Settings

The first interactive launch opens setup and fetches the release catalogs
in the background. Save your choices or press Esc to save the defaults.
Open settings again with F2 from the channel menu.
On keyboards without function keys, press Alt plus a digit, or press Ctrl+F
and then the digit. Use 0 for F10. For example, Alt+2 opens Settings in the
channel menu, and Ctrl+F followed by 9 opens Instance copies.

- GitHub token is optional. It is masked in the TUI and sent only with
  requests to the GitHub Daily releases API. Public access is available by default.
- Remove DefaultServerList defaults to Off. When enabled, the updater
  removes the mod from the prepared instance before launching its Prism test.
- Transfer extra mods defaults to Off. When enabled, confirming the
  clean test opens the extra JAR selection automatically.

Settings are stored in `~/.gtnh-updater/settings.json` inside the user's
home directory. On Windows this is `%USERPROFILE%/.gtnh-updater/settings.json`.
Settings from the previous location are migrated on launch. The token, if
saved, is stored in the file. The file is owner-only on Unix and inherits
the user's profile permissions on Windows.

## Find a version source

Run `gtnh-updater` in an interactive terminal. Choose Stable, Beta / RC,
or Daily with Up/Down and Enter. Then type part of a version to filter that
channel. Fuzzy search accepts partial input such as `292`, `rc2`, or `855`.
Select a version with Enter, then choose a Prism/MultiMC ZIP with Up/Down
and Enter to see its direct download link. Press Enter or Right again to
start the download. The progress screen shows bytes received and a percentage
when the server reports the total size. Press Esc or Left to cancel.

Right Arrow also opens the highlighted item. Press Left Arrow or Esc to go
back one screen when a download is inactive. At the channel list, Esc quits.
Press Ctrl+Esc to quit from any screen when the terminal reports that
shortcut. Ctrl+C also quits directly.

Opening a channel reads its local catalog and refreshes it in the background. Press
F5 in the version list to request a fresh copy of that channel. Stable
and Beta / RC use the official release index. Daily queries the public
GitHub Releases API and falls back to the latest ten releases on the
public website if the API is unavailable. The channel indexes
`stable.json`, `beta.json`, and `daily.json` live next to `settings.json`.
Existing cached indexes are migrated from the previous cache location. An unsuccessful
refresh keeps the last good catalog. A first offline run with no cached
catalogs shows an empty list until a fetch succeeds.

Completed archives are saved in `gtnh-updater-<id>` subdirectories of the
system temporary directory. Before any network request for an archive, the
updater checks its cache by exact download URL, file size, SHA-256 and ZIP
structure. The cache index in `~/.gtnh-updater/downloads/` works across
launches. Missing ZIPs, archives without a recorded hash, and archives
that fail validation are downloaded again. Cancelled or failed downloads
remove their partial files.
The updater does not change any Prism or MultiMC instances during download.

### Install a fresh instance

After the ZIP is ready, press F3 (**Install as new**) instead of choosing an
instance to update. Select a detected launcher instances folder, or use F2
to enter an existing folder by absolute path. The updater installs a new
instance with its own folder and a versioned name. Existing instances,
worlds, configs, and extra mods stay where they are. If enabled, **Remove
DefaultServerList** also applies to the fresh installation.

### Command line

For scripts, use the direct command.

```text
gtnh-updater source 2.9.2
gtnh-updater source 2.9.0-RC-2
gtnh-updater source daily-2026-11-24+855
gtnh-updater prepare /path/to/instances/GTNH /path/to/client.zip
```

Stable releases and beta/RC builds point to the official version history.
Daily builds use the exact GitHub release tag, including the build number
that distinguishes builds published on the same day. The command works
offline. It checks the ID format. Check archive availability at the source.

## Prepare a candidate instance

After downloading a ZIP in the TUI, press Enter to open the instance picker.
It detects instances in common Prism Launcher and MultiMC data directories,
including folders configured in either launcher. Type part of a name or path
to filter the list. Press Tab to browse other folders. In the folder browser,
type to filter by name or paste an absolute path to jump to it. Choose an
instance containing `instance.cfg` and `mmc-pack.json`.

The updater extracts the new pack in a separate
`.gtnh-updater-<id>/instance` directory beside the installed instance, on
the same filesystem. The directory also contains an update plan identifying
the source instance. Press Esc to cancel extraction and remove the incomplete
candidate.

The installed instance stays active. For a detected Prism profile, select
**Test launch** on the completed preparation screen. The updater registers
the clean candidate as a separate `gtnh-updater-test-<id>` instance and
runs Prism with `--dir` and `--launch`. It reuses the profile's account and
launcher settings. Check that Minecraft reaches its main menu before
migrating worlds and personal settings.
The test instance displays a `[Test <version>]` prefix in Prism. On
activation the new pack's original display name is restored. Saved copies
are labeled `[Backup]` or `[After rollback]` and include the version when known.
Once Prism registration succeeds, the update plan moves into the test
instance and the empty staging directory is removed. Staging stays beside
the installed instance so registration can move the candidate atomically.
The downloaded ZIP uses the system temporary directory.

The updater saves the current preparation and Prism test stage in
`~/.gtnh-updater/active-update.json`. Use **F6: Resume update** from the
channel menu to reopen the latest candidate after exiting the updater.
Resuming uses the prepared files directly and requires no ZIP.

### Review configuration changes

Launch the prepared instance through **Test launch** in Prism, wait for the
Minecraft main menu, and quit the game. Press F4 to confirm the completed
test. The updater opens **Migrate data and configs** after any extra mod step.
It compares files shipped in `config/` and `configs/` after
the test launch. The comparison ignores files created only at runtime,
paths under a `generated` directory, `created` metadata, and full-line `#`
comments. Plain text is compared by line regardless of CRLF or LF. Merged
lines use the target's line endings and keep its comments.
Valid JSON is compared by property. JSON key order
and formatting are ignored, and arrays are reviewed as a whole.
The file list is on the left and the selected file's changes are on
the right. On narrow terminals the panels stack vertically. Press Tab to
switch focus and Up or Down to move in the focused panel. Press Left to
keep the old value or Right to keep the new value. PgUp and PgDn scroll the
preview. Config choices and F10 require a terminal at least 44 columns
wide and 16 rows high when changes are present. Type part of a file name
or path to filter the list. Backspace
edits the filter, and Escape clears it before closing the screen. F5 selects
the new side for every change. Close Minecraft in both instances before
pressing F10. It copies player data and applies config choices in the same
operation, including choices for files outside the current filter.
If the updater exits during migration, reopening it restores the test
instance's pre-migration files unless the migration already completed.
Use F6 to resume after recovery.
If extra mod transfer is enabled, Enter copies the selected JARs or explicitly
skips the step when the selection is empty. The config review opens next.
If you go back from either screen, Enter resumes the remaining step.
The updater records the shipped
mod inventory during preparation. On later updates, identified extra mods
are preselected. For an instance without an inventory, select the JARs you
added. The target pack's own mod versions are kept.
After migration, press Enter to continue to activation or Escape to
return to the channel menu. From the channel menu, press F6 to resume.
Press F7 or Enter to review activation. Quit Minecraft and fully exit Prism
Launcher before pressing Y. An open launcher can overwrite the display names
with cached values. The migrated instance takes over the original path and the
old copy is marked `[Backup …]`. Reopen Prism to see the new entries.

Unselected changes keep the target's content. A saved choice is preselected
when the same change and context recur. Choices are stored as fingerprints
in `~/.gtnh-updater/config-choices.json`, under the same home directory as
the app settings. Files the updater cannot compare are omitted from the
review and keep the target pack's contents.

## Update and rollback

Updates follow Method 1 in the [GTNH Installing and Migrating guide](https://wiki.gtnewhorizons.com/wiki/Installing_and_Migrating#Method_1:_Migrating_to_a_New_Instance_(Recommended)).
Keep a recoverable copy of the current saves and verify that the clean target
launches before migrating player data. Preparation and testing use a
separate instance until you confirm activation.

Migration copies these entries when present:

- Worlds and player data in `saves`.
- Map and marker data in `journeymap`, `TCNodeTracker`, and `visualprospecting`.
- Player content in `schematics`, `screenshots`, `resourcepacks`, and `shaderpacks`.
- Personal settings in `ESM`, `BotaniaVars.dat`, `localconfig.cfg`,
  `options.txt`, `optionsnf.txt`, and `servers.dat`.
If the test instance already has a world with the same folder name, migration
stops to protect both copies. Shared mod folders such as `saves/NEI` are
merged like other player data.

The target supplies its pack configuration and mods. Extra mods and manual
configuration changes need review. Activation retains the complete previous
instance in a sibling `.gtnh-updater-backup-<id>` directory.

Press F8 in the channel menu to roll back the latest activation. Close
both Minecraft instances and Prism, then confirm with Y in the rollback
screen. Escape or Left cancels and returns to the channel menu. The prior
instance returns to its original path and display name. The newer copy,
including progress made after activation, remains in
`.gtnh-updater-displaced-<id>`. Its path is shown after rollback. Rollback
switches entire instances. Worlds from the newer copy stay with that copy.
After an interruption, reopening the updater recovers the pending switch.

### Manage instance copies

From the channel menu, press F9 to see staging directories, test instances,
backups, and displaced instances in known launcher instance directories.
Select a copy to see its path. Press Delete, close Minecraft and its launcher,
and press Y to confirm permanent deletion. The current update and the backup
used by F8 are protected.
