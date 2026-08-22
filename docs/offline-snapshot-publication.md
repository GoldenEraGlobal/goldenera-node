# Trusted snapshot publication

Automatic trusted snapshots are generated from a consistent RocksDB checkpoint of the running production node.
The live canonical database is held under `masterChainLock` only while RocksDB creates its bounded checkpoint;
all rewind, export, compression and verification work runs against the isolated sibling clone.

The published anchor is never the raw tip. It is the canonical block at least 24 hours behind the captured head:

```text
lagBlocks = ceil(24 hours / canonical NetworkParams targetMiningTime)
snapshotHeight = capturedHeadHeight - max(lagBlocks, configuredSafetyOverride)
```

At a 30-second target interval this is 2,880 blocks. A configured override can only increase the lag. A head at
700,000 therefore immediately permits generation at 697,120 when the required canonical history and entity undo
data are available.

The clone rewinds canonical height/head, block and transaction indexes, and token/authority/validator materialized
indexes. Missing or corrupt entity undo data makes the trie/entity completeness proof fail closed and no publication
is selected. Historical trie nodes remain available because the state trie is content-addressed and has no deletion
or garbage-collection path.

Core-only generation has no PostgreSQL or explorer dependency. Explorer artifacts are optional and are published
independently only when explorer is enabled and exactly caught up to the selected core anchor.

Trusted HTTP consumers inspect all configured HTTPS sources, select the highest semantically valid manifest, and
only fail over among sources carrying the same height/hash. They never silently downgrade. Dynamic production
heights are accepted only by the trusted-origin policy and still require complete genesis-to-anchor block, TD,
transaction-root, state-trie and entity verification. P2P/untrusted paths continue to require hardcoded checkpoints.

Before activation, every missing, corrupt or untrusted snapshot leaves canonical storage untouched and falls back
to optimized P2P sync. Only an ambiguous failure after a durable activation journal may stop readiness.

The explicit troubleshooting command remains available:

```bash
java -jar goldenera-node.jar snapshot-publish \
  current \
  /srv/goldenera/snapshots/current \
  https://node-eu2.goldenera.global/
```

Add `with-explorer` as the final argument only when explorer output is required.
