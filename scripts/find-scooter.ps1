# Find the Ola head unit on the local network.
#
# It pings the IPv6 all-nodes multicast on every active adapter, reads the
# neighbour table, then tests TCP 50005 on every link-local neighbour.
# The row marked OPEN is a machine with the root daemon.
#
# Usage:
#   .\find-scooter.ps1
#   .\find-scooter.ps1 -Port 50005 -TimeoutMs 800

[CmdletBinding()]
param(
    [int]$Port = 50005,
    [int]$TimeoutMs = 600
)

$ErrorActionPreference = "SilentlyContinue"

Write-Host "Pinging the IPv6 neighbourhood ..."
$adapters = Get-NetAdapter | Where-Object { $_.Status -eq "Up" }
foreach ($a in $adapters) {
    if (-not $a.InterfaceIndex) { continue }
    ping -6 -n 1 -w 400 "ff02::1%$($a.InterfaceIndex)" | Out-Null
}
Start-Sleep -Milliseconds 600

function Test-Port6 {
    param([string]$Address, [int]$Port, [int]$Ms)

    $client = [System.Net.Sockets.TcpClient]::new(
        [System.Net.Sockets.AddressFamily]::InterNetworkV6)
    try {
        $ip = [System.Net.IPAddress]::Parse($Address)   # accepts %scope
        $task = $client.ConnectAsync($ip, $Port)
        return $task.Wait($Ms) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

$neighbours = Get-NetNeighbor -AddressFamily IPv6 |
    Where-Object {
        $_.IPAddress -like "fe80::*" -and
        $_.LinkLayerAddress -and
        $_.State -ne "Unreachable"
    }

if (-not $neighbours) {
    Write-Host "No IPv6 neighbours. Is IPv6 enabled on the Wi-Fi adapter?"
    Write-Host "  Enable-NetAdapterBinding -Name 'Wi-Fi' -ComponentID ms_tcpip6"
    exit 1
}

$rows = foreach ($n in $neighbours) {
    $target = "$($n.IPAddress)%$($n.InterfaceIndex)"
    $open = Test-Port6 -Address $target -Port $Port -Ms $TimeoutMs
    [pscustomobject]@{
        IPv6      = $n.IPAddress
        Scope     = $n.InterfaceIndex
        MAC       = $n.LinkLayerAddress
        Adapter   = $n.InterfaceAlias
        Port50005 = if ($open) { "OPEN" } else { "closed" }
    }
}

$rows |
    Sort-Object @{ Expression = { $_.Port50005 -eq "OPEN" }; Descending = $true },
                IPv6 |
    Format-Table -AutoSize

$hit = $rows | Where-Object { $_.Port50005 -eq "OPEN" } | Select-Object -First 1
if ($hit) {
    Write-Host ""
    Write-Host "Scooter found:" -ForegroundColor Green
    Write-Host "  .\open-settings.ps1 -IPv6 $($hit.IPv6) -Scope $($hit.Scope)"
} else {
    Write-Host ""
    Write-Host "No host answered on port $Port. Wake the scooter and try again."
}
