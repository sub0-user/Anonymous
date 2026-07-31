# Anonymous — Installation scripts

Smooth, single-command installers for the main platforms. They unpack the built app
image (a self-contained jlink runtime — **no JDK required on the target machine**),
create launchers/shortcuts/desktop entries, and always keep the `~/.anonymous` data
directory untouched on uninstall.

## 1. Build the distributable first

```bash
./gradlew jlinkZip
```

Produces `build/distributions/app-<platform>.zip` (a complete runtime with the
`anonymous` launcher inside `bin/`).

## 2. Install

| Platform   | Command |
|------------|---------|
| Linux (Ubuntu, Debian, others) | `bash scripts/install.sh` |
| Windows    | `powershell -ExecutionPolicy Bypass -File scripts\install.ps1` |

### Linux options

```
--zip PATH       app zip (default: auto-detected)
--prefix PATH    install dir (default: ~/.local/share/anonymous)
--bin-dir PATH   launcher symlink dir (default: ~/.local/bin)
--no-desktop     skip the .desktop entry
--uninstall      remove the installation (keeps ~/.anonymous)
```

On Ubuntu/Debian the only prerequisite is `unzip` (`sudo apt install unzip`).

### Windows options

```
-Zip <path>       app zip (default: auto-detected)
-NoDesktop        skip the Start Menu shortcut
-Uninstall        remove the installation (keeps %USERPROFILE%\.anonymous)
```

## 3. Uninstall

- Linux: `bash scripts/uninstall.sh` (or `bash scripts/install.sh --uninstall`)
- Windows: `powershell -ExecutionPolicy Bypass -File scripts\uninstall.ps1`

## Follow-up (Phase 4)

`.deb` (Ubuntu/Debian) and `.msi` (Windows) packages via `jpackage`, built on top of the
same jlink image — additive, the installers above keep working unchanged.
