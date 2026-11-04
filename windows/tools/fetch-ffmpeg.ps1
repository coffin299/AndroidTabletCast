$ErrorActionPreference = "Stop"
$dest = Join-Path $PSScriptRoot "..\ffmpeg\bin"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
# latest は 8.x / 9.x だけになった。AutoGen 7.1.1 は avcodec-61 の 7.1 系が必要。
$url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-07-31-14-10/ffmpeg-n7.1.5-12-g1fdbca85aa-win64-lgpl-shared-7.1.zip"
$zip = Join-Path $env:TEMP "ffmpeg-n7.1.5-win64-lgpl-shared.zip"
$extract = Join-Path $env:TEMP "ffmpeg-n7.1.5-extract"
if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
Invoke-WebRequest -Uri $url -OutFile $zip
Expand-Archive -Path $zip -DestinationPath $extract -Force
$bin = Get-ChildItem -Path $extract -Recurse -Directory | Where-Object { $_.Name -eq "bin" } | Select-Object -First 1
if ($null -eq $bin) { throw "FFmpeg の bin が見つかりません" }
Copy-Item (Join-Path $bin.FullName "*.dll") $dest -Force
Write-Output "copied to $dest"
