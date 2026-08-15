# Cross-SDK cache format fixtures

These three files were **emitted by lever-swift's own `CacheStore`**, not written
by hand. They pin spec 0002 §7's format promise from the other side: one cache
format, not one per SDK.

The promise is **schema- and value-identity, proven by decoding** — byte equality
of serializer output is explicitly *not* promised, because Swift's `JSONEncoder`
and kotlinx.serialization own their member order and number spelling, and no
cache file ever crosses SDKs on a real device (spec 0003 §7).

`StorageTests` decodes every file here unmodified and then round-trips its own
encoder through its own decoder, asserting the same schema and the same values.

## How they were produced

In a checkout of `lever-swift`, a throwaway `@testable` test constructed a
`CacheStore` over a temp directory, called `loadOrCreateClientId()`, and `save`d
two `CachedSnapshot`s — one with an ETag and every wire type, one with a null
ETag and no values (the two shapes a snapshot file can take). The emitted files
were copied here verbatim and the generator deleted. Re-record the same way if
the schema ever bumps.
