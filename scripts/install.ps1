# Anonymous — Windows installer.
param(
  [string]$Zip = "",
  [switch]$Uninstall,
  [switch]$NoDesktop
)
$ErrorActionPreference = "Stop"
$AppName = "Anonymous"
$InstallDir = Join-Path $env:LOCALAPPDATA "Anonymous"
$DataDir = Join-Path $HOME ".anonymous"

function Write-Log($m) { Write-Host "[install] $m" -ForegroundColor Cyan }
function Write-Err($m) { Write-Host "[install] ERROR: $m" -ForegroundColor Red; exit 1 }

if ($Uninstall) {
  if (Test-Path $InstallDir) { Remove-Item -Recurse -Force $InstallDir }
  Remove-Item (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Anonymous.lnk") -ErrorAction SilentlyContinue
  Write-Log "Uninstalled. Your chat data in $DataDir was kept."
  exit 0
}

if (-not $Zip) {
  $Zip = Get-ChildItem (Join-Path $PSScriptRoot "..\build\distributions\app-*.zip") -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Zip) { Write-Err "No app zip found - build it first: .\gradlew.bat jlinkZip (or pass -Zip)" }
if (-not (Test-Path $Zip)) { Write-Err "Zip not found: $Zip" }

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
$tmp = Join-Path $env:TEMP ("anonymous-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
  Expand-Archive -Path $Zip -DestinationPath $tmp -Force
  $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
  if (-not $inner) { Write-Err "Zip did not contain an app image" }
  Get-ChildItem $InstallDir | Remove-Item -Recurse -Force
  Copy-Item -Recurse -Force (Join-Path $inner.FullName "*") $InstallDir
} finally {
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

$launcher = Join-Path $InstallDir "bin\anonymous.bat"
if (-not (Test-Path $launcher)) { $launcher = Join-Path $InstallDir "bin\anonymous" }

if (-not $NoDesktop) {
  $ws = New-Object -ComObject WScript.Shell
  $lnk = $ws.CreateShortcut((Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Anonymous.lnk"))
  $lnk.TargetPath = $launcher
  $lnk.WorkingDirectory = Split-Path $launcher
  $lnk.Save()
}

Write-Log "Installed to $InstallDir"
Write-Log "Run it from the Start Menu (Anonymous) or: $launcher"
Write-Log "Data directory: $DataDir  |  Uninstall: .\scripts\install.ps1 -Uninstall"
