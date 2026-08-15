import com.android.build.api.artifact.SingleArtifact
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlinx.validation.api.dump
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "dev.forcetower.lever"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true

        // CI emulator setup is a known tar pit, so the instrumented smoke test
        // runs on a Gradle-managed device from M1 rather than being discovered
        // late (plan 0003 M1).
        managedDevices.localDevices.create("smokeApi30") {
            device = "Pixel 2"
            apiLevel = 30
            systemImageSource = "aosp-atd"
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // "a newer version exists" is not a defect, and letting someone else's
        // release break this build is not a check — it is a network dependency.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }
}

kotlin {
    // The public surface is a deliberate act (spec 0003 §1).
    explicitApi()
    jvmToolchain(17)

    compilerOptions {
        // A warning in an SDK is a defect its consumers inherit.
        allWarningsAsErrors = true
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // KSerializer is in the public surface (spec 0003 §2), so this is api, not implementation.
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// The unit suites replay the HTTP contract fixtures from a pinned checkout of
// the lever monorepo (spec 0003 §1). CI exports the path; locally a sibling
// checkout is used if one is there.
tasks.withType<Test>().configureEach {
    val fromEnvironment = providers.environmentVariable("LEVER_CONTRACT_FIXTURES")
    val sibling = layout.projectDirectory.dir("../../lever/packages/contract-fixtures/fixtures/http")
    val fixtures = fromEnvironment.orElse(sibling.asFile.absolutePath)
    inputs.dir(fixtures.map { file(it) }).optional().withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("lever.contractFixtures", fixtures.get())
}

// MARK: ABI validation
//
// The public surface is frozen against a committed dump from M1 (spec 0003 §1).
// Of the two candidates, the standalone kotlinx validator is the one that works
// here: the Kotlin Gradle plugin's built-in ABI validation emits nothing at all
// for AGP's built-in-Kotlin Android compilations, and the validator's own Gradle
// plugin registers no tasks unless the Kotlin plugin is applied — which AGP 9
// refuses. So its dump engine is driven directly, over the **published AAR**,
// which makes the check a statement about exactly what consumers resolve.

abstract class AbiDumpTask : DefaultTask() {
    @get:InputFile abstract val aar: RegularFileProperty

    @get:OutputFile abstract val dump: RegularFileProperty

    @get:Internal abstract val workDirectory: DirectoryProperty

    @TaskAction
    fun dumpAbi() {
        val work = workDirectory.get().asFile
        work.mkdirs()
        val classes = File(work, "classes.jar")
        ZipFile(aar.get().asFile).use { archive ->
            val entry =
                requireNotNull(archive.getEntry("classes.jar")) { "the aar carries no classes.jar" }
            archive.getInputStream(entry).use { source ->
                classes.outputStream().use { source.copyTo(it) }
            }
        }
        val signatures = JarFile(classes).use { jar -> jar.loadApiFromJvmClasses() }.filterOutNonPublic()
        dump.get().asFile.writeText(StringBuilder().also { signatures.dump(it) }.toString())
    }
}

/**
 * The androidx layout: `api/current.api` tracks the surface under development,
 * and each release freezes a copy beside it (`api/0.1.0.api`). The frozen files
 * are the history — diffable in review, immutable once tagged.
 */
abstract class AbiCheckTask : DefaultTask() {
    @get:InputFile abstract val actual: RegularFileProperty

    // Not tracked as inputs: `apiDump` writes these same paths, and a check
    // that depended on the task it guards would regenerate what it verifies.
    // The task never goes up to date instead (it is a text comparison).
    @get:Internal abstract val current: ConfigurableFileCollection

    /** The release's frozen file, when this build's version has one. */
    @get:Internal abstract val frozen: ConfigurableFileCollection

    @get:Input abstract val version: Property<String>

    @TaskAction
    fun check() {
        val reference = current.singleOrNull()
        check(reference != null && reference.isFile) {
            "no committed ABI dump — run ./gradlew :lever:apiDump and commit lever/api/current.api"
        }
        val dumped = actual.get().asFile.readText()
        check(reference.readText() == dumped) {
            "the public ABI changed. Review the diff, then run ./gradlew :lever:apiDump to " +
                "accept it:\n" + diff(reference.readText().lines(), dumped.lines())
        }

        val release = frozen.singleOrNull()?.takeIf { it.isFile } ?: return
        check(release.readText() == dumped) {
            "the frozen ABI of ${version.get()} does not match the current surface. A released " +
                "API is history: bump the version rather than editing ${release.name}.\n" +
                diff(release.readText().lines(), dumped.lines())
        }
    }

    private fun diff(expected: List<String>, actual: List<String>): String {
        val removed = expected.filterNot { it in actual }.map { "- $it" }
        val added = actual.filterNot { it in expected }.map { "+ $it" }
        return (removed + added).joinToString("\n")
    }
}

abstract class AbiFreezeTask : DefaultTask() {
    @get:InputFile abstract val current: RegularFileProperty

    @get:OutputFile abstract val frozen: RegularFileProperty

    @TaskAction
    fun freeze() {
        val source = current.get().asFile
        val target = frozen.get().asFile
        check(source.isFile) { "run ./gradlew :lever:apiDump first" }
        check(!target.isFile || target.readText() == source.readText()) {
            "${target.name} is already frozen with a different surface. A released API is " +
                "history — bump the version instead of rewriting it."
        }
        source.copyTo(target, overwrite = true)
        logger.lifecycle("froze the public ABI as api/${target.name}")
    }
}

androidComponents.onVariants(androidComponents.selector().withName("release")) { variant ->
    val apiDirectory = layout.projectDirectory.dir("api")
    val releaseVersion =
        providers.gradleProperty("VERSION_NAME").map { it.removeSuffix("-SNAPSHOT") }

    val generate =
        tasks.register<AbiDumpTask>("abiDump") {
            description = "Dumps the release AAR's public ABI."
            aar = variant.artifacts.get(SingleArtifact.AAR)
            dump = layout.buildDirectory.file("api/current.api")
            workDirectory = layout.buildDirectory.dir("api/work")
        }

    tasks.register<Copy>("apiDump") {
        description = "Accepts the current public ABI as api/current.api."
        from(generate.flatMap { it.dump })
        into(apiDirectory)
    }

    tasks.register<AbiFreezeTask>("apiFreeze") {
        description = "Freezes api/current.api as this version's history (api/<version>.api)."
        current = apiDirectory.file("current.api")
        frozen = releaseVersion.map { apiDirectory.file("$it.api") }
    }

    val verify =
        tasks.register<AbiCheckTask>("apiCheck") {
            description = "Fails when the public ABI drifts from the committed dump."
            outputs.upToDateWhen { false }
            actual = generate.flatMap { it.dump }
            current.from(apiDirectory.file("current.api"))
            frozen.from(releaseVersion.map { apiDirectory.file("$it.api") })
            version = releaseVersion
        }
    tasks.named("check") { dependsOn(verify) }
}

mavenPublishing {
    // Which of the two upload tasks runs is the workflow's call:
    // `publishToMavenCentral` leaves a user-managed deployment for a human to
    // validate in the Portal, `publishAndReleaseToMavenCentral` releases it
    // outright. A tag build does the latter (plan 0003 M1).
    publishToMavenCentral()
    signAllPublications()

    // The artifactId would otherwise default to the Gradle project name (`lever`),
    // and the coordinates consumers are promised in the README are immutable the
    // moment the first release lands.
    coordinates(artifactId = "lever-android")

    pom {
        name = "lever-android"
        description = "The Android client SDK for lever, a self-hosted remote config service."
        url = "https://github.com/ForceTower/lever-android"
        licenses {
            license {
                name = "MIT License"
                url = "https://github.com/ForceTower/lever-android/blob/main/LICENSE"
            }
        }
        developers {
            developer {
                id = "forcetower"
                name = "João Sena"
                url = "https://github.com/ForceTower"
            }
        }
        scm {
            url = "https://github.com/ForceTower/lever-android"
            connection = "scm:git:git://github.com/ForceTower/lever-android.git"
            developerConnection = "scm:git:ssh://git@github.com/ForceTower/lever-android.git"
        }
    }
}
