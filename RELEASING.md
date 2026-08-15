# Releasing

Coordinates: `dev.forcetower.lever:lever-android`, published to Maven Central
through the Central Portal. Central releases are **immutable** — a version that
lands can never be replaced, only superseded.

## One-time setup

1. **Verify the namespace.** Claim `dev.forcetower` in the Central Portal and
   verify it by DNS TXT record. A verified namespace covers its subgroups, so
   `dev.forcetower.lever` needs no separate claim.
2. **Create a signing key.** `gpg --full-generate-key`, publish it
   (`gpg --keyserver keyserver.ubuntu.com --send-keys <id>`), and export the
   private key: `gpg --armor --export-secret-keys <id>`.
3. **Store the secrets** in the repository's `maven-central` environment:
   - `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` — a Portal user token.
   - `SIGNING_KEY` — the armored private key.
   - `SIGNING_KEY_PASSWORD` — its passphrase.

## Rehearsing without releasing

Before the first real publication, prove the pipe end to end without making
anything immutable:

```bash
./gradlew publishToMavenCentral --no-configuration-cache
```

This uploads a **user-managed deployment**. In the Portal, wait for it to reach
`VALIDATED`, resolve it by coordinates from a consumer through the Portal's
authenticated manual-testing repository, and then **drop** the deployment. The
`publish` workflow's `workflow_dispatch` entry does the same from CI.

## Cutting a release

1. Land everything, with `./gradlew build` green (unit suites, lint, the ABI
   check, and the sample's minified build).
2. Set `VERSION_NAME` in `gradle.properties` to the release version (no
   `-SNAPSHOT`).
3. `./gradlew :lever:apiDump && ./gradlew :lever:apiFreeze` — the first accepts
   the current surface into `api/current.api`, the second freezes
   `api/<version>.api` as history. Review both diffs: this is the moment the
   public API becomes a promise.
4. Commit, tag `<version>`, push the tag. The `publish` workflow checks that the
   tag matches `VERSION_NAME` and that the frozen ABI exists, builds, signs, and
   publishes.
5. Bump `VERSION_NAME` to the next `-SNAPSHOT`.

0.x is where the API is still allowed to move. 1.0 is tagged only after the
flagship migration has shipped on lever in production (spec 0003 §9).
