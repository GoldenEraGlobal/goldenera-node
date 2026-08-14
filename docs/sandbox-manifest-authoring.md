# Offline sandbox manifest authoring

Sandbox manifests use the real node genesis implementation. A draft must be a
strict schema-v1 manifest whose `genesis.expectedGenesisHash` is exactly:

```text
0x0000000000000000000000000000000000000000000000000000000000000000
```

That placeholder is rejected by normal node manifest loading. It is accepted
only by the explicit offline authoring command:

```shell
mise exec -- java -jar target/goldenera-node-0.1.1.jar \
  sandbox-manifest-author \
  /absolute/path/draft.json \
  /absolute/path/manifest.json
```

The command creates isolated temporary RocksDB world state, calculates genesis
through `GenesisCandidateFactory` and `SandboxGenesisPlanFactory`, substitutes
the exact hash, reloads the final canonical bytes through the production
manifest loader, and calls `createVerified`. It then traverses every path
component with no-follow, descriptor-bound directory handles and publishes via
an exclusive reservation plus an atomic descriptor-relative move. Concurrent
publishers cannot replace an existing path.

Linux/default filesystems normally provide descriptor-bound secure directory
streams. When the Java filesystem provider does not expose them (including the
default macOS provider), authoring uses a portable fallback: every existing
component is inspected with no-follow, directory and file keys must remain
stable before and after I/O, output is reserved with `CREATE_NEW`, and final
replacement is an atomic move. This detects directory substitution and remains
safe between cooperating concurrent publishers, but the Java fallback cannot
eliminate the final path-rename race against a malicious process with write
access to the same parent directory. Authoring outputs should therefore live in
an owner-controlled directory.

On success, stdout contains stable machine-readable lines:

```text
genesisHash=0x...
manifestFingerprint=...
manifest=/absolute/path/manifest.json
```

Exit status is `0` for success, `2` for invalid command usage, and `1` for an
authoring or validation failure. The command does not start Spring, open a node
database, use configured mainnet/testnet state, or print manifest contents.
