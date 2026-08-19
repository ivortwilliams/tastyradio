<#
.SYNOPSIS
    Builds the signed APK and publishes it as a GitHub release, so every phone running Tasty Radio
    is offered the update the next time it opens.

.DESCRIPTION
    One command, because a hobby project that takes six steps to ship stops getting shipped.

        .\scripts\release.ps1 -Notes "Fixed the Gregorian stream"

    It reads the version out of android/app/build.gradle.kts (bump versionCode there first, or nobody's
    phone will notice the new build), builds, writes the version.json the in-app updater reads, and
    uploads both to a new release tagged with the version name.

    The asset names never change on purpose: the updater asks GitHub for
    /releases/latest/download/version.json, which only resolves if every release calls it that.

.PARAMETER Notes
    What changed, in a sentence. This is what your friends see in the update dialog, so write it
    for them rather than for git.
#>
param(
    [Parameter(Mandatory = $true)][string]$Notes,
    [string]$Repo = "ivortwilliams/tastyradio"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $root "android")

$gradle = Get-Content "app/build.gradle.kts" -Raw
if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') { throw "No versionCode in android/app/build.gradle.kts" }
$versionCode = [int]$Matches[1]
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { throw "No versionName in android/app/build.gradle.kts" }
$versionName = $Matches[1]
$tag = "v$versionName"

Write-Host "Releasing Tasty Radio $versionName (versionCode $versionCode) to $Repo" -ForegroundColor Cyan

if (& git status --porcelain) { Write-Warning "Working tree is dirty — releasing it anyway." }

& .\gradlew.bat assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$staging = Join-Path $root "android/build/release"
New-Item -ItemType Directory -Force $staging | Out-Null
$apk = Join-Path $staging "TastyRadio.apk"
Copy-Item "app/build/outputs/apk/release/app-release.apk" $apk -Force

# What the app itself reads to decide whether it is out of date.
$manifest = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    notes       = $Notes
    apkUrl      = "https://github.com/$Repo/releases/latest/download/TastyRadio.apk"
}
$manifestPath = Join-Path $staging "version.json"
$manifest | ConvertTo-Json | Set-Content $manifestPath -Encoding utf8

# A tag that already exists means this version was published before; bump versionCode instead of
# overwriting, or phones that already updated will never be told about the change.
$existing = & gh release view $tag --repo $Repo 2>$null
if ($LASTEXITCODE -eq 0) { throw "Release $tag already exists. Bump versionCode/versionName first." }

& gh release create $tag $apk $manifestPath --repo $Repo --title "Tasty Radio $versionName" --notes $Notes
if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }

Write-Host ""
Write-Host "Published. Share this link:" -ForegroundColor Green
Write-Host "  https://github.com/$Repo/releases/latest/download/TastyRadio.apk"
Write-Host "Phones already running Tasty Radio will be offered $versionName next time they open it."
