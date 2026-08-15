package dev.forcetower.lever.sample

import android.app.Application
import android.util.Log
import dev.forcetower.lever.Lever
import dev.forcetower.lever.LeverConfiguration
import dev.forcetower.lever.LeverContext
import dev.forcetower.lever.LeverKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Keys are declared once, with their defaults, and read through the operator —
 * the shape spec 0003 §2.2 documents and the reason this module exists:
 * building the library proves nothing about the consumption promise.
 */
object Flags {
    val enableEnrollment = LeverKey.boolean("enable_enrollment", default = false)
    val maxRetries = LeverKey.int("max_retries", default = 3)
    val greeting = LeverKey.string("greeting", default = "hello")
    val paywall = LeverKey.json("paywall", default = PaywallConfig())
}

@Serializable
data class PaywallConfig(val headline: String = "Go Pro", val cta: String = "Start trial")

class SampleApp : Application() {
    private val scope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        Lever.configure(
            this,
            LeverConfiguration(
                baseUrl = BASE_URL,
                clientKey = CLIENT_KEY,
                context = LeverContext(appVersion = "1.0.0", attributes = mapOf("tier" to "free")),
                // The DEBUG recipe is configuration, not a bypass flag.
                minimumFetchInterval = if (BuildConfig.DEBUG) Duration.ZERO else 12.hours,
                cacheNamespace = "prod",
            ),
        )

        val lever = Lever.shared
        Log.i("lever-sample", "enrollment=${lever[Flags.enableEnrollment]} retries=${lever[Flags.maxRetries]}")

        scope.launch {
            lever.updates.collect { update ->
                Log.i("lever-sample", "activated version=${update.version} changed=${update.changedKeys}")
            }
        }
    }

    private companion object {
        const val BASE_URL = "https://lever.example"
        const val CLIENT_KEY = "pk_sample"
    }
}
