package dev.forcetower.lever.storage

import dev.forcetower.lever.WireValue
import dev.forcetower.lever.leverJson
import dev.forcetower.lever.logging.LeverLogSink
import dev.forcetower.lever.logging.error
import dev.forcetower.lever.logging.warn
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
// Explicitly the nio one: kotlin.io ships a same-named exception by default import.
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * The last-activated snapshot, as it lives on disk (spec 0002 §7).
 *
 * [values] is the **raw wire payload**, untouched: the cache replays a resolve
 * response rather than re-encoding typed values, so read semantics are
 * identical from cache and from network by construction.
 */
internal data class CachedSnapshot(
    val version: Int,
    val etag: String?,
    val values: Map<String, WireValue>,
    val fetchedAt: Long,
    val activatedAt: Long,
)

/**
 * Two files under `{cacheDirectory}/lever/`, splitting what must survive a
 * credential rotation (the identity) from what a rotation may discard (a
 * snapshot). Nothing here throws: a cache is a floor, not a dependency.
 *
 * There are no Android APIs in this codec — it is `java.io`/`java.nio` only, so
 * the storage suites run as plain JVM tests against temp directories. That
 * portability has a limit worth remembering: the host JVM permits filesystem
 * operations Android's app-private storage does not, so a publication primitive
 * is only proven by the instrumented suite.
 */
internal class CacheStore(
    val directory: File,
    private val keyHash: String,
    private val sink: LeverLogSink,
) {
    val identityFile: File get() = File(directory, "identity.json")
    val snapshotFile: File get() = File(directory, "$keyHash.json")

    // MARK: identity

    /**
     * The installation identifier: stable across key rotation, environments,
     * and contexts, because it is the future rollout bucketing key — one that
     * reshuffled on every credential rotation would re-randomize every
     * percentage rollout (spec 0002 §7).
     *
     * First creation publishes with an **atomic rename**, and the loser of a
     * race re-reads the winner's file.
     */
    fun loadOrCreateClientId(): String = synchronized(publication) { createOrLoadIdentity() }

    private fun createOrLoadIdentity(): String {
        createDirectory()

        val existing = readIdentity()
        when (existing) {
            is IdentityRead.Found -> return existing.clientId
            IdentityRead.OutOfReach -> {
                // Credential-encrypted storage before the first unlock, most of
                // all: the file is there and will be readable again later.
                // Minting a replacement is wrong and *persisting* one would
                // destroy the installation's real identity, so this id is
                // deliberately volatile — it lasts for this process and touches
                // nothing on disk.
                sink.warn("identity file could not be read — using a volatile client id")
                return UUID.randomUUID().toString().lowercase(Locale.ROOT)
            }
            IdentityRead.Absent, IdentityRead.Unusable -> Unit
        }

        val generated = UUID.randomUUID().toString().lowercase(Locale.ROOT)
        val payload = leverJson.encodeToString(IdentityFile(SCHEMA_VERSION, generated))

        // Bytes that were read and are not an identity are not someone else's
        // identity either, so there is nothing to lose by replacing them.
        if (existing == IdentityRead.Unusable) return overwriteIdentity(generated)

        return when (publishExclusively(payload)) {
            Publication.CREATED -> generated
            // Someone else won between the read and the rename; their identity
            // is the one on disk. Overwriting is safe only when the re-read
            // proves the file unusable — never when it merely could not be read.
            Publication.ALREADY_EXISTS ->
                when (val reread = readIdentity()) {
                    is IdentityRead.Found -> reread.clientId
                    IdentityRead.Unusable -> overwriteIdentity(generated)
                    IdentityRead.Absent, IdentityRead.OutOfReach -> generated
                }
            // Nothing was published, so a readable file can only be someone
            // else's — preferring it keeps a failed write from minting a second
            // identity for an installation that already has one.
            Publication.FAILED -> (readIdentity() as? IdentityRead.Found)?.clientId ?: generated
        }
    }

    /**
     * Why absence and unreadability are not the same answer: collapsing them
     * makes an unreadable file look like a first run, and the first run path
     * both mints a new identity *and* writes it over the one already there.
     */
    private sealed interface IdentityRead {
        data class Found(val clientId: String) : IdentityRead

        /** No file — a genuine first run. */
        data object Absent : IdentityRead

        /** A file that cannot be read *right now*. Never a reason to write. */
        data object OutOfReach : IdentityRead

        /** Read, but not an identity: wrong schema, bad JSON, not a uuid. */
        data object Unusable : IdentityRead
    }

    private fun readIdentity(): IdentityRead {
        // Not `readText`: it answers `null` for a missing file and for one it
        // was refused, and `File.isFile` is itself false when the stat is
        // denied. Only the nio exceptions separate the two.
        val raw =
            try {
                String(Files.readAllBytes(path(identityFile)), Charsets.UTF_8)
            } catch (_: NoSuchFileException) {
                return IdentityRead.Absent
            } catch (_: IOException) {
                return IdentityRead.OutOfReach
            } catch (_: SecurityException) {
                return IdentityRead.OutOfReach
            }
        val file =
            try {
                leverJson.decodeFromString<IdentityFile>(raw)
            } catch (_: RuntimeException) {
                sink.warn("identity file is unreadable — regenerating the client id")
                return IdentityRead.Unusable
            }
        if (file.schemaVersion != SCHEMA_VERSION) {
            sink.warn("identity file is unreadable — regenerating the client id")
            return IdentityRead.Unusable
        }
        // Spec 0002 §7 defines the persisted identity as a lowercase UUID, and
        // spec 0001 §6.2 caps clientId at 64 chars, so anything unparseable is
        // corrupt.
        val canonical = canonicalUuid(file.clientId)
        if (canonical == null) {
            sink.warn("client id is not a uuid — regenerating")
            return IdentityRead.Unusable
        }
        if (canonical != file.clientId) {
            // Same installation, wrong spelling. Rewriting beats regenerating:
            // the client id is the rollout bucketing key, and two SDKs sharing a
            // cache directory that each regenerated on the other's casing would
            // reshuffle every percentage rollout forever (spec 0002 §12.1).
            sink.warn("client id was not canonical — rewriting it lowercase")
            return IdentityRead.Found(overwriteIdentity(canonical))
        }
        return IdentityRead.Found(file.clientId)
    }

    private fun canonicalUuid(raw: String): String? {
        // UUID.fromString is lenient about short groups; the round trip is what
        // makes this a real parse.
        val parsed =
            try {
                UUID.fromString(raw)
            } catch (_: IllegalArgumentException) {
                return null
            }
        val canonical = parsed.toString().lowercase(Locale.ROOT)
        return if (canonical.equals(raw, ignoreCase = true)) canonical else null
    }

    private fun overwriteIdentity(clientId: String): String {
        write(leverJson.encodeToString(IdentityFile(SCHEMA_VERSION, clientId)), identityFile, "identity")
        return clientId
    }

    private enum class Publication { CREATED, ALREADY_EXISTS, FAILED }

    /**
     * Write-then-rename, not an exclusive create.
     *
     * An exclusive create publishes the *name* before the bytes, so a racing
     * reader can find an empty file, decide it is corrupt, and overwrite the
     * winner's identity with its own. A rename publishes a fully written file in
     * one atomic step, which makes "the file exists" mean "the file is complete"
     * — the property the loser's re-read depends on (spec 0002 §12).
     *
     * It is `rename(2)` rather than the `link(2)` this originally used because
     * Android refuses hard links inside app-private storage with
     * `AccessDeniedException`: the link form never published at all, failing on
     * the *first* write and handing back an identity that had never reached
     * disk. Only the instrumented suite could catch that — the host JVM the
     * storage tests run on permits links.
     */
    private fun publishExclusively(payload: String): Publication {
        val temporary = File(directory, ".identity-${UUID.randomUUID()}.tmp")
        return try {
            temporary.writeText(payload)
            Files.move(path(temporary), path(identityFile))
            Publication.CREATED
        } catch (_: FileAlreadyExistsException) {
            Publication.ALREADY_EXISTS
        } catch (cause: IOException) {
            sink.error("client id could not be persisted error=${cause.message}")
            Publication.FAILED
        } catch (cause: UnsupportedOperationException) {
            sink.error("client id could not be persisted error=${cause.message}")
            Publication.FAILED
        } finally {
            temporary.delete()
        }
    }

    // MARK: snapshot

    /**
     * `null` for a first run, and for every unusable file: the snapshot file is
     * a cache, so a schema bump discards rather than migrates (spec 0002 §7).
     */
    fun loadSnapshot(): CachedSnapshot? {
        val raw = readText(snapshotFile) ?: return null
        val file =
            try {
                leverJson.decodeFromString<SnapshotFile>(raw)
            } catch (_: RuntimeException) {
                sink.warn("cache file is corrupt — treating as a first run")
                return null
            }
        if (file.schemaVersion != SCHEMA_VERSION) {
            sink.warn(
                "cache file schema is unknown schemaVersion=${file.schemaVersion} — discarding"
            )
            return null
        }
        if (file.version < 0) {
            sink.warn("cache file version is negative — treating as a first run")
            return null
        }
        // Unix seconds are non-negative. Letting a negative one through would
        // reach the scheduler's elapsed-time arithmetic, turning a corrupt cache
        // into wrong scheduling instead of a first run (spec 0002 §12.1).
        if (file.fetchedAt < 0 || file.activatedAt < 0) {
            sink.warn("cache file timestamps are out of range — treating as a first run")
            return null
        }
        return CachedSnapshot(
            version = file.version,
            etag = file.etag,
            values = file.values,
            fetchedAt = file.fetchedAt,
            activatedAt = file.activatedAt,
        )
    }

    /**
     * A write failure logs and changes nothing about the in-memory activation:
     * reads serve the new snapshot, only the floor is stale (spec 0002 §7).
     */
    fun save(snapshot: CachedSnapshot) {
        createDirectory()
        val payload =
            try {
                leverJson.encodeToString(
                    SnapshotFile(
                        schemaVersion = SCHEMA_VERSION,
                        version = snapshot.version,
                        etag = snapshot.etag,
                        values = snapshot.values,
                        fetchedAt = snapshot.fetchedAt,
                        activatedAt = snapshot.activatedAt,
                    )
                )
            } catch (_: RuntimeException) {
                sink.error("cache snapshot could not be encoded version=${snapshot.version}")
                return
            }
        write(payload, snapshotFile, "cache")
    }

    // MARK: file plumbing

    private fun createDirectory() {
        if (directory.isDirectory) return
        if (!directory.mkdirs() && !directory.isDirectory) {
            sink.error("cache directory could not be created path=${directory.path}")
        }
    }

    private fun readText(file: File): String? =
        try {
            if (file.isFile) file.readText() else null
        } catch (_: IOException) {
            null
        }

    /** Atomic replace: a reader either sees the old file or the new one. */
    private fun write(payload: String, target: File, what: String) {
        val temporary = File(directory, ".${target.name}-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(payload)
            Files.move(
                path(temporary),
                path(target),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (cause: IOException) {
            temporary.delete()
            sink.error("$what write failed error=${cause.message}")
        }
    }

    private fun path(file: File) = file.toPath()

    private companion object {
        const val SCHEMA_VERSION = 1

        /**
         * Held across the whole read-or-create, and shared by every [CacheStore]
         * because racing initializers are separate instances over one directory.
         *
         * `rename(2)` publishes atomically but does not *arbitrate*: a move
         * without `REPLACE_EXISTING` is a check followed by a rename, so two
         * threads can both find the name free and the second silently replaces
         * the first — which is one identity persisted and two handed out. The
         * lock makes the in-process case, the one an app actually hits when
         * several entry points initialize at once, exact.
         */
        val publication = Any()
    }
}

@Serializable
private data class IdentityFile(val schemaVersion: Int, val clientId: String)

/**
 * All fields are required except `etag`, which is nullable — the file exists
 * only once something has been activated, so there is no half-empty state to
 * represent (spec 0002 §7).
 */
@Serializable
private data class SnapshotFile(
    val schemaVersion: Int,
    val version: Int,
    val etag: String?,
    val values: Map<String, WireValue>,
    val fetchedAt: Long,
    val activatedAt: Long,
)
