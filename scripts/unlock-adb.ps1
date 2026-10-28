# Make adb work on an Ola S1 head unit (reversible).
#
# You do NOT need this for the Settings script. The gRPC channel is already
# root. Use this only if you want adb / scrcpy / normal file pulls.
#
# What it does:
#   1. makes an adb key on this PC (if you do not have one)
#   2. uploads the PUBLIC key to /data/local/tmp/adb_keys_mine
#   3. sets scootermode=serv (opens the port and restarts adbd)
#   4. bind-mounts your key over the key file adbd reads
#   5. adb connect [addr%scope]:5555
#
# Nothing is written to a partition. A reboot clears the bind mount.
#
# Usage:
#   .\unlock-adb.ps1 -IPv6 fe80::1234:5678:9abc:def0
#   .\unlock-adb.ps1 -IPv6 fe80::1234:5678:9abc:def0 -Mode mfg
#   .\unlock-adb.ps1 -IPv6 fe80::1234:5678:9abc:def0 -Undo

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$IPv6,

    [int]$Scope = 0,

    [ValidateSet("serv", "mfg")]
    [string]$Mode = "serv",

    [switch]$Undo,

    [string]$Adb = ""
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$ola  = Join-Path $here "ola.py"

$py = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $py) { $py = (Get-Command py -ErrorAction SilentlyContinue).Source }
if (-not $py) { throw "Python 3 not found." }

# ---- find adb -------------------------------------------------------------
if (-not $Adb) {
    if ($env:ADB -and (Test-Path $env:ADB)) {
        $Adb = $env:ADB
    } else {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) {
            $Adb = $cmd.Source
        } elseif (Test-Path "C:\Program Files\DroidCam\Client\data\obs-plugins\droidcam-obs\adb\adb.exe") {
            $Adb = "C:\Program Files\DroidCam\Client\data\obs-plugins\droidcam-obs\adb\adb.exe"
        }
    }
}
if (-not $Adb) { throw "adb not found. Install platform-tools or pass -Adb <path>." }

# ---- auto scope -----------------------------------------------------------
if ($IPv6 -match '^(?i)fe80' -and $IPv6 -notmatch '%' -and $Scope -eq 0) {
    $n = Get-NetNeighbor -AddressFamily IPv6 -ErrorAction SilentlyContinue |
         Where-Object { $_.IPAddress -eq $IPv6 } |
         Select-Object -First 1
    if ($n) { $Scope = [int]$n.InterfaceIndex }
}

$common = @("--ipv6", $IPv6)
if ($Scope -gt 0) { $common += @("--scope", "$Scope") }

if ($IPv6 -match '%') {
    $hostPort = "[$IPv6]:5555"
} elseif ($IPv6 -match '^(?i)fe80') {
    if ($Scope -eq 0) {
        throw "Link-local address needs a scope. Use fe80::1%6 or -Scope 6."
    }
    $hostPort = "[${IPv6}%${Scope}]:5555"
} else {
    $hostPort = "[$IPv6]:5555"
}

function Invoke-Ola {
    param([string[]]$Arguments)
    & $py $ola @Arguments @common
    if ($LASTEXITCODE -ne 0) { throw "ola.py failed: $($Arguments -join ' ')" }
}

# ---- undo -----------------------------------------------------------------
if ($Undo) {
    Write-Host "Removing the adb key mount ..."
    try { Invoke-Ola -Arguments @("shell", "--raw", "umount /adbkeymoveos3_pub_prod") } catch { }
    try { Invoke-Ola -Arguments @("shell", "--raw", "umount /adb_keys") } catch { }
    Write-Host "Returning to customer mode ..."
    Invoke-Ola -Arguments @("shell", "--raw", "setprop persist.ola.security.scootermode cust")
    & $Adb disconnect $hostPort | Out-Null
    Write-Host "Done. Reboot also clears everything."
    exit 0
}

# ---- 1. key ---------------------------------------------------------------
$keyDir = Join-Path $env:USERPROFILE ".android"
$pubKey = Join-Path $keyDir "adbkey.pub"
if (-not (Test-Path $pubKey)) {
    Write-Host "Making an adb key ..."
    New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
    & $Adb keygen (Join-Path $keyDir "adbkey")
    if ($LASTEXITCODE -ne 0) { throw "adb keygen failed." }
}

# ---- 2. push the public key ----------------------------------------------
Write-Host "Uploading public key ..."
Invoke-Ola -Arguments @("push", $pubKey, "/data/local/tmp/adb_keys_mine")
Invoke-Ola -Arguments @("shell", "--raw", "chmod 644 /data/local/tmp/adb_keys_mine")

# ---- 3. mode --------------------------------------------------------------
Write-Host "Setting scootermode=$Mode ..."
Invoke-Ola -Arguments @("shell", "--raw", "setprop persist.ola.security.scootermode $Mode")
Start-Sleep -Seconds 2

# ---- 4. bind mount --------------------------------------------------------
# The daemon splits on whitespace, so `mount` must run from a script.
$bind = @'
#!/system/bin/sh
MODE="$1"
if [ "$MODE" = "mfg" ]; then T=/adb_keys; else T=/adbkeymoveos3_pub_prod; fi
mount | grep -q " $T " && umount "$T"
mount --bind /data/local/tmp/adb_keys_mine "$T" || exit 1
echo "bound $T"
'@
$tmp = Join-Path $env:TEMP "ola_bind_key.sh"
Set-Content -Path $tmp -Value $bind -Encoding ascii -NoNewline
Write-Host "Binding the key over the factory key file ..."
Invoke-Ola -Arguments @("push", $tmp, "/data/local/tmp/bind_key.sh")
Invoke-Ola -Arguments @("shell", "--raw", "chmod 755 /data/local/tmp/bind_key.sh")
Invoke-Ola -Arguments @("shell", "--raw", "sh /data/local/tmp/bind_key.sh $Mode")
Start-Sleep -Seconds 1

# ---- 5. connect -----------------------------------------------------------
Write-Host "Connecting adb to $hostPort ..."
& $Adb connect $hostPort
& $Adb devices -l

Write-Host ""
Write-Host "If it says 'device', you are in. Try:  adb shell id" -ForegroundColor Green
Write-Host "Undo with:  .\unlock-adb.ps1 -IPv6 $IPv6 -Undo"
