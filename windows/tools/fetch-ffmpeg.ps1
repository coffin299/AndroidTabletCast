$ErrorActionPreference = "Stop"
$dest = Join-Path $PSScriptRoot "..\ffmpeg\bin"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n7.1.1-latest-win64-lgpl-shared.zip"
$zip = Join-Path $env:TEMP "ffmpeg-n7.1.1-win64-lgpl-shared.zip"
$extract = Join-Path $env:TEMP "ffmpeg-n7.1.1-extract"
if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
Invoke-WebRequest -Uri $url -OutFile $zip
Expand-Archive -Path $zip -DestinationPath $extract -Force
$bin = Get-ChildItem -Path $extract -Recurse -Directory | Where-Object { $_.Name -eq "bin" } | Select-Object -First 1
if ($null -eq $bin) { throw "FFmpeg の bin が見つかりません" }
Copy-Item (Join-Path $bin.FullName "*.dll") $dest -Force
Write-Output "copied to $dest"
