# Open Android Settings on an Ola S1 head unit.
#
# Usage:
#   .\open-settings.ps1 -IPv6 fe80::1234:5678:9abc:def0
#   .\open-settings.ps1 -IPv6 fe80::1234:5678:9abc:def0 -Diag
#   .\open-settings.ps1 -IPv6 fe80::1234:5678:9abc:def0 -Home
#
# The scooter must be awake and reachable over IPv6 on TCP 50005.
# No adb, no pairing, no password.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$IPv6,

    [int]$Scope = 0,

    [switch]$Diag,
    [switch]$Home,
    [switch]$Fresh
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$ola  = Join-Path $here "ola.py"

$py = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $py) { $py = (Get-Command py -ErrorAction SilentlyContinue).Source }
if (-not $py) {
    throw "Python 3 not found. Install it from python.org and tick 'Add to PATH'."
}

# One dependency. Install it on first run.
& $py -c "import grpc" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Installing grpcio (one time) ..."
    & $py -m pip install -r (Join-Path $here "requirements.txt")
    if ($LASTEXITCODE -ne 0) { throw "pip install failed." }
}

# A link-local address needs the interface number. Find it from the neighbour
# table so the user does not have to look it up.
if ($IPv6 -match '^(?i)fe80' -and $IPv6 -notmatch '%' -and $Scope -eq 0) {
    $n = Get-NetNeighbor -AddressFamily IPv6 -ErrorAction SilentlyContinue |
         Where-Object { $_.IPAddress -eq $IPv6 } |
         Select-Object -First 1
    if ($n) { $Scope = [int]$n.InterfaceIndex }
}

$common = @("--ipv6", $IPv6)
if ($Scope -gt 0) { $common += @("--scope", "$Scope") }

if ($Home) {
    $action = @("home")
} elseif ($Diag) {
    $action = @("diag")
} else {
    $action = @("settings")
    if ($Fresh) { $action += "--fresh" }
}

Write-Host "Talking to $IPv6 (scope $Scope) ..."
& $py $ola @action @common
exit $LASTEXITCODE
