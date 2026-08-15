# Releasing

Coordinates: `dev.forcetower.lever:lever-android`, published to Maven Central
through the Central Portal. Central releases are **immutable** — a version that
lands can never be replaced, only superseded.

## One-time setup

1. **Verify the namespace.** `dev.forcetower` is claimed and verified in the
   Central Portal. A verified namespace covers its subgroups, so
   `dev.forcetower.lever` needs no separate claim.
2. **Publish the signing key.** Central resolves the public half from a keyserver
   to check the signature, so it has to be there before the first release:

   ```bash
   gpg --keyserver keyserver.ubuntu.com --send-keys <fingerprint>
   ```

   Confirm it is actually retrievable afterwards — a key that is present locally
   but absent from the keyserver signs happily and fails validation:

   ```bash
   curl -s "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x<fingerprint>"
   ```

3. **Store the secrets** in the repository's `maven-central` environment:
   - `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` — a Portal user token,
     generated from <https://central.sonatype.com/account>.
   - `SIGNING_KEY` — the armored private key,
     `gpg --armor --export-secret-keys <fingerprint>`.
   - `SIGNING_KEY_PASSWORD` — the key's passphrase, if it has one. The workflow
     passes an empty value when the secret is unset; set the two together, since
     a passphrase without a matching re-export fails signing in CI.

   The environment allows only the `main` branch and `*.*.*` tags to deploy.

## Rehearsing without releasing

Prove the pipe end to end without making anything immutable. Run the `publish`
workflow from the Actions tab with the `release` input left **off**: it builds
and signs the bare `VERSION_NAME` and uploads a **user-managed deployment**. In
the Portal, wait for it to reach `VALIDATED`, resolve it by coordinates from a
consumer through the Portal's authenticated manual-testing repository, and then
**drop** the deployment — a rehearsal that is never dropped will collide with
the release of the same version.

The local equivalent, which needs the credentials as Gradle properties:

```bash
./gradlew publishToMavenCentral -PRELEASE=true --no-configuration-cache
```

## Versions

`VERSION_NAME` in `gradle.properties` is the version **under development**,
written bare (`0.2.0`). Every build appends `-SNAPSHOT` unless it is given
`-PRELEASE=true`, which only the `publish` workflow does. So there is no suffix
to strip before a release and none to put back afterwards — the one edit per
cycle is bumping to the next version, and forgetting it cannot publish a bare
version by accident.

## Cutting a release

1. Land everything, with `./gradlew build` green (unit suites, lint, the ABI
   check, and the sample's minified build).
2. Check that `VERSION_NAME` is the version you mean to release.
3. `./gradlew :lever:apiDump && ./gradlew :lever:apiFreeze` — the first accepts
   the current surface into `api/current.api`, the second freezes
   `api/<version>.api` as history. Review both diffs: this is the moment the
   public API becomes a promise.
4. Commit, tag `<version>`, push the tag. The `publish` workflow checks that the
   tag matches `VERSION_NAME` and that the frozen ABI exists; then it builds,
   signs, and **releases to Central automatically**
   (`publishAndReleaseToMavenCentral`). There is no button to press afterwards
   and nothing to take back.
5. Bump `VERSION_NAME` to the next version.

The manual `workflow_dispatch` path defaults to the rehearsal above; ticking its
`release` input runs the same guards and releases outright, which is the escape
hatch for a tag build that died after the tag was already pushed.

0.x is where the API is still allowed to move. 1.0 is tagged only after the
flagship migration has shipped on lever in production (spec 0003 §9).
