# lever-android

The Android client SDK for [lever](https://github.com/ForceTower/lever), a
self-hosted remote config service.

Targeting rules are evaluated **on the server**. This SDK sends context, receives
fully resolved values, caches them, and serves them synchronously — it contains
no rule engine.

> **Config values are public.** Anything you publish is readable by every end
> user of your app. Never put a secret in a config value.
>
> **`pk_…` client keys are identifiers, not credentials.** They authorize a
> read-only surface for one environment. Shipping one in your APK is the
> intended use.

## Install

```kotlin
// settings.gradle.kts → dependencyResolutionManagement { repositories { mavenCentral() } }
dependencies {
    implementation("dev.forcetower.lever:lever-android:0.1.0")
}
```

`minSdk 26`. The SDK brings kotlinx-coroutines, kotlinx-serialization-json,
OkHttp, and androidx.lifecycle-process — nothing else.

## Configure once, at launch

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        Lever.configure(
            this,
            LeverConfiguration(
                baseUrl = "https://config.example.dev",
                clientKey = BuildConfig.LEVER_KEY,           // pk_…, an identifier
                context = LeverContext(
                    appVersion = BuildConfig.VERSION_NAME,
                    attributes = mapOf("cohort" to "beta"),
                ),
                // DEBUG builds see console changes on the next launch; this is
                // configuration, not a bypass flag.
                minimumFetchInterval = if (BuildConfig.DEBUG) Duration.ZERO else 12.hours,
                // Pin the cache to a name you control, so a client-key rotation
                // still lands on a warm cache. Recommended.
                cacheNamespace = "prod",
            ),
        )
    }
}
```

`configure` reads one small cache file on the calling thread — a few
milliseconds, once, at process start. That is deliberate: it is what makes the
first read after `configure` correct rather than eventually correct. StrictMode
will notice it in `Application.onCreate`; that is the documented placement, and
the alternative (lazy loading) moves the same I/O somewhere less predictable.

Reading `Lever.shared` before `configure`, or calling `configure` twice, throws
`IllegalStateException` — there is no half-configured singleton quietly serving
defaults. For a second environment (tests, staging), construct a `LeverClient`
directly; `shared` is sugar, not a registry.

## Declare keys once

```kotlin
object Flags {
    val enableEnrollment = LeverKey.boolean("enable_enrollment", default = false)
    val maxRetries = LeverKey.int("max_retries", default = 3)
    val greeting = LeverKey.string("greeting", default = "hello")
    val paywall = LeverKey.json("paywall", default = PaywallConfig.Standard)
}

if (Lever.shared[Flags.enableEnrollment]) { … }
```

Each key carries its own default, so the fallback cannot drift between call
sites. Reads are **synchronous, non-optional, and never throw**: a missing key,
a wrong type, or a `json` payload that fails to decode resolves to the key's
default and logs.

`json` keys resolve their serializer where the key is declared, so the module
that declares one applies kotlinx.serialization's compiler plugin and marks the
model `@Serializable`:

```kotlin
plugins { id("org.jetbrains.kotlin.plugin.serialization") version "…" }

@Serializable
data class PaywallConfig(val headline: String, val cta: String) {
    companion object { val Standard = PaywallConfig("Go Pro", "Start trial") }
}
```

Prefer immutable models: "stable between activations" is a promise about the
SDK's storage, not about aliasing a decoded instance. For a type whose
serializer cannot be reified, pass it explicitly:
`LeverKey.json("paywall", default, PaywallConfig.serializer())`.

Types map as `Boolean ↔ boolean`, `String ↔ string`,
`Int`/`Long`/`Double ↔ number`, `@Serializable T ↔ json`. An integer key needs a
value exactly representable in its range — a fractional or out-of-range number
falls back to the default.

> **The integer bound.** The service stores numbers as JSON numbers, so it
> cannot preserve integers beyond the JavaScript safe range (|n| ≤ 2⁵³ − 1). The
> decoder is faithful to whatever lexeme arrives — a `long` key will happily
> decode `9007199254740993` — but do not author values beyond that bound: they
> may already have been rounded before the SDK ever sees them.

### Layering lever over another source

`lookup` is the same read, reporting absence instead of absorbing it:

```kotlin
lever.lookup(Flags.enableEnrollment)   // Boolean? — null when lever is silent
```

`null` means this environment has nothing the key can serve: not published, or
present but unreadable as the declared type. It exists for exactly one caller —
a composite that puts lever in front of another config source. `get`/`value`
commit to the code default the moment lever is silent, which would shadow every
layer beneath it; `lookup` lets the caller fall through and keep the code
default as the floor under *all* of them. Everywhere else, read the
non-optional way.

## Fetch and activate

```kotlin
lever.fetch()               // suspend; stages, reads are unchanged
lever.activate()            // synchronous; true when the serving values changed
lever.fetchAndActivate()    // both
```

Fetched values are **staged** until `activate()`, so reads are stable inside a
frame, a screen, or a session. `activate()` is a durability boundary: when it
returns, the new snapshot is already on disk.

Automatic fetches honor `minimumFetchInterval`; explicit ones always hit the
network — the interval throttles the SDK, not the developer.

## React to changes

```kotlin
lifecycleScope.launch {
    lever.updates.collect { update ->
        // update.version, update.changedKeys
    }
}
```

`updates` emits on every **value-changing** activation; a republish whose
resolved values are identical for this client commits silently. Every collector
gets its own buffer, so a slow one never blocks the SDK and never misses an
update. In Compose, bridge it with `collectAsState`.

## Realtime nudges

While foregrounded, the SDK keeps a Server-Sent Events connection open. The
stream carries **version numbers only, never values**: on a nudge the SDK runs
its normal fetch, activates it (opt out with `autoActivateOnNudge = false`), and
publishes an update. A dead stream degrades to polling at the interval — never
to broken config.

## The three-layer floor

1. Live values from the server.
2. The last **activated** values, from disk.
3. Your code defaults.

An unreachable server means stale config, never a broken app. A 401 (a rotated
key) stops fetching but never clears the cache; a corrupt cache file is treated
as a first run; a failed cache write leaves the in-memory activation standing.
This is a tested guarantee, not an intent: see
[`FloorTests`](lever/src/test/kotlin/dev/forcetower/lever/FloorTests.kt), which
walks every one of those failures end to end through the public API.

## Cache-only readers

```kotlin
LeverClient(context, LeverConfiguration(…, automaticUpdates = false))
```

No automatic fetch, no timer, no lifecycle observation, no stream — reads serve
the cache, and an explicit `fetch()` still works as a deliberate override. Point
several clients at one cache directory with **one writer** and the rest as
readers.

Configure lever in your **main process**. `ProcessLifecycleOwner` and the cache's
single-writer model are per-process; a client in a second process should be a
cache-only reader.

## Cache location

`{cacheDirectory ?: context.noBackupFilesDir}/lever/`. The default keeps Android
Auto Backup from cloning the installation identity onto new devices, and from
restoring a stale snapshot. Set `cacheDirectory` and you own that story.

## Logging

```kotlin
LeverConfiguration(…, logSink = { level, message -> Timber.log(level.toPriority(), message) })
```

Everything the SDK says goes through the sink; the default writes to logcat
under the tag `Lever`. The sink may be called from any thread, so make it
thread-safe. It may read flags (`get`/`value`) while handling a message, but it
must not call `activate()`, `fetchAndActivate()`, or `close()` — those would
deadlock on the commit that is invoking it.

## Teardown

`LeverClient.close()` releases the runtime thread, the timer, the stream, and the
HTTP client's pools. Reads keep serving the last activated snapshot afterwards —
a closed client degrades to a static one — while control operations throw.
`Lever.shared` is process-lived and refuses to close.

## Development

```bash
./gradlew build                    # unit suites, lint, the ABI check, the sample's R8 build
./gradlew :lever:apiDump           # accept a public-API change into api/current.api
./gradlew :lever:apiFreeze         # freeze api/<version>.api at release time
./gradlew :lever:smokeApi30DebugAndroidTest   # instrumented smoke on a managed device
```

The public surface is frozen by a committed ABI dump (`lever/api/current.api`),
with one frozen file per release beside it. The HTTP contract fixtures come from
the lever monorepo, pinned by SHA in `.contract-fixtures-sha`; bumping that pin
is how this SDK syncs with the service's contract.

## License

MIT — see [LICENSE](LICENSE).
