$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root "protocol\fixtures"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$packets = New-Object System.Collections.Generic.List[byte]
$payload = New-Object System.Collections.Generic.List[byte]

function Add-Bytes([System.Collections.Generic.List[byte]]$list, [byte[]]$data) {
    foreach ($item in $data) { $list.Add($item) }
}
function Add-Be([System.Collections.Generic.List[byte]]$list, [byte[]]$little) {
    [array]::Reverse($little)
    Add-Bytes $list $little
}
function Add-U8([int]$value) { $payload.Add([byte]($value -band 255)) }
function Add-U16([int]$value) { Add-Be $payload ([BitConverter]::GetBytes([uint16]($value -band 65535))) }
function Add-U32([int64]$value) { Add-Be $payload ([BitConverter]::GetBytes([uint32]$value)) }
function Add-U64([int64]$value) { Add-Be $payload ([BitConverter]::GetBytes($value)) }
function Add-Raw([byte[]]$data) { Add-Bytes $payload $data }
function Add-Name([string]$text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($text)
    Add-U8 $bytes.Length
    Add-Raw $bytes
}
function Flush-Frame([int]$type) {
    $packets.Add([byte]$type)
    $length = [BitConverter]::GetBytes([uint32]$payload.Count)
    [array]::Reverse($length)
    Add-Bytes $packets $length
    Add-Bytes $packets $payload.ToArray()
    $payload.Clear()
}

$tablet = [byte[]](1..16)
$nonce = [byte[]](0xA0..0xAF)
$token = [byte[]](1..32)
$pc = New-Object byte[] 16
for ($i = 0; $i -lt 16; $i++) { $pc[$i] = [byte](0xC0 + $i) }
$sha = [Security.Cryptography.SHA256]::Create()
$key = $sha.ComputeHash($token)
$hmacAlgo = New-Object Security.Cryptography.HMACSHA256 (, $key)
$hmac = $hmacAlgo.ComputeHash($nonce)

Add-U16 1
Add-Raw $tablet
Add-U8 1
Add-Raw $nonce
Add-U16 1920
Add-U16 1080
Add-U16 2560
Add-U16 1600
Add-Name "Tab"
Flush-Frame 1

Add-U8 0
Add-Name "123456"
Flush-Frame 2

Add-U8 1
Add-Raw $pc
Add-Raw $hmac
Flush-Frame 2

foreach ($code in 0, 1, 2, 3, 4) {
    Add-U8 $code
    Flush-Frame 3
}

Add-Raw $pc
Add-Name "Desk"
Flush-Frame 5

Add-Raw $tablet
Add-Raw $token
Flush-Frame 4

$annex = [byte[]](0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E, 0, 0, 0, 1, 0x68, 0xCE, 0x06, 0xE2)
Add-U8 0
Add-U16 1920
Add-U16 1080
Add-Raw $annex
Flush-Frame 16

Add-U64 1000
Add-U8 1
Add-Raw ([byte[]](0, 0, 0, 1, 0x65, 0x88))
Flush-Frame 17

Add-U32 48000
Add-U8 2
Add-Raw ([byte[]](0x11, 0x90))
Flush-Frame 18

Add-U64 2000
Add-Raw ([byte[]](0xAA, 0xBB))
Flush-Frame 19

Add-U8 0
Add-U16 32768
Add-U16 100
Flush-Frame 32

Add-U8 0
Add-U16 29
Add-U32 0
Flush-Frame 33

Add-U16 1
Add-U16 2
Add-U16 -120
Flush-Frame 34

Add-U8 2
Flush-Frame 48

Flush-Frame 49

Add-U64 123456789
Flush-Frame 126

Add-U64 123456789
Add-U64 123456800
Flush-Frame 127

[IO.File]::WriteAllBytes((Join-Path $outDir "packets.bin"), $packets.ToArray())

$beacon = New-Object System.Collections.Generic.List[byte]
Add-Bytes $beacon ([Text.Encoding]::ASCII.GetBytes("ATC1"))
$port = [BitConverter]::GetBytes([uint16]39721)
[array]::Reverse($port)
Add-Bytes $beacon $port
Add-Bytes $beacon $tablet
$name = [Text.Encoding]::UTF8.GetBytes("Tab")
$beacon.Add([byte]$name.Length)
Add-Bytes $beacon $name
[IO.File]::WriteAllBytes((Join-Path $outDir "beacon.bin"), $beacon.ToArray())
Write-Output ("packets {0} beacon {1}" -f $packets.Count, $beacon.Count)
