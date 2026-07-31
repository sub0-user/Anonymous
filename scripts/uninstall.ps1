# Anonymous — Windows uninstaller (thin wrapper).
param()
$ErrorActionPreference = "Stop"
& (Join-Path $PSScriptRoot "install.ps1") -Uninstall
