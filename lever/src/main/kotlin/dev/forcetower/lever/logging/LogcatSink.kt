package dev.forcetower.lever.logging

import android.util.Log

/**
 * The default sink: `android.util.Log` under the tag `Lever`.
 *
 * Messages are not redacted — lever's diagnostics are key names, versions, and
 * HTTP statuses, and config values are public by design (research 0001 §6).
 */
public class LogcatSink : LeverLogSink {
    override fun log(level: LeverLogLevel, message: String) {
        when (level) {
            LeverLogLevel.DEBUG -> Log.d(TAG, message)
            LeverLogLevel.INFO -> Log.i(TAG, message)
            LeverLogLevel.WARN -> Log.w(TAG, message)
            LeverLogLevel.ERROR -> Log.e(TAG, message)
        }
    }
}

// Not a companion constant: `const` in a companion object leaks a public static
// field into the ABI, and the tag is not part of the contract.
private const val TAG = "Lever"

