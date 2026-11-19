param(
    [string]$AvdName,
    [switch]$List
)

$ErrorActionPreference = 'Stop'
$sdkDirs = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
$propertiesFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'local.properties'
if (Test-Path -LiteralPath $propertiesFile) {
    foreach ($line in Get-Content -LiteralPath $propertiesFile -Encoding UTF8) {
        if ($line -match '^\s*sdk\.dir\s*=(.*)$') {
            $sdkDirs += $Matches[1].Trim().Replace('\:', ':').Replace('\ ', ' ').Replace('\\', '\')
            break
        }
    }
}
if ($env:LOCALAPPDATA) {
    $sdkDirs += Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}

$emulatorPath = $null
foreach ($sdkDir in $sdkDirs) {
    if ($sdkDir) {
        $candidate = Join-Path $sdkDir 'emulator\emulator.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $emulatorPath = $candidate
            break
        }
    }
}
if (-not $emulatorPath) {
    $command = Get-Command emulator.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($command) { $emulatorPath = $command.Source }
}
if (-not $emulatorPath) {
    throw 'Android Emulator not found. Install it in SDK Manager and configure local.properties or ANDROID_SDK_ROOT/ANDROID_HOME.'
}

$avds = @(& $emulatorPath -list-avds)
if ($LASTEXITCODE -ne 0) { throw 'Failed to list Android virtual devices.' }
$avds = @($avds | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($avds.Count -eq 0) {
    throw 'No AVD found. Create an Android TV API 28 device in Android Studio Device Manager first.'
}
if ($List) {
    $avds
    exit 0
}
if (-not $AvdName) {
    if ($avds.Count -eq 1) {
        $AvdName = $avds[0]
    } else {
        Write-Host 'Available AVDs:'
        $avds | ForEach-Object { Write-Host "  $_" }
        $AvdName = Read-Host 'Enter the AVD name to start'
    }
}
if ($avds -cnotcontains $AvdName) {
    throw "Unknown AVD '$AvdName'. Run with -List to see available names."
}

Start-Process -FilePath $emulatorPath -ArgumentList @('-avd', ('"{0}"' -f $AvdName)) -WindowStyle Hidden
Write-Host "Starting $AvdName. Wait for Android to finish booting before running scripts\install_emulator.bat."
