# Proto files

Two files, and only one of them is right.

| File | Status | Use it? |
|---|---|---|
| `wifican.proto` | **Correct.** Recovered from the daemon binary | Yes |
| `wifican-first-attempt.proto` | Wrong guess from before the recovery | No, history only |

## Where `wifican.proto` comes from

`/vendor/bin/diagnostics_server` is a Go program. Every Go binary built with
`protoc-gen-go` carries its own `FileDescriptorProto` inside it — a compact,
machine-readable copy of the `.proto` that generated the code.

Walking that descriptor at file offset ~9.99 MB gives the exact schema the
server implements: 40 methods, real message names, real field numbers. The file
in this folder is that descriptor rendered back to `.proto` text.

That is why it can be trusted. It is not a guess, and it is not copied from
Ola's source. It is what the shipped binary tells you about itself.

## Why the first attempt is still here

Because it is a useful lesson. A guessed proto can appear to work: the first
attempt called `ExecuteADBCommand` correctly and got root, which made it look
right. It was still wrong about 23 other methods and invented three message
types that do not exist.

Keep that in mind when you reverse engineer anything else. One working call
proves one call, not the schema.

## Using it

Generate a Python client if you want typed calls:

```sh
python -m pip install grpcio-tools
python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. wifican.proto
```

The scripts in this repo do not need that. `scripts/ola.py` builds the one
message it needs by hand (a single length-delimited string field), which keeps
the dependency list at just `grpcio`.

## Do not call these

They change vehicle state, wipe credentials, or reflash an ECU:

`SwUpdateFileClearCache`, `SwUpdateFileTransferMeta`, `SwUpdateFileTransfer`,
`SwUpdateTriggerUpgrade`, `SwUpdateTestSetStatus`, `ProvisionVin`,
`ProvisionConfig`, `ScooterModeChange`, `RemoveWifiCredentials`,
`GenerateCerts`, `RegisterInstallCerts`, `Deinit`.
