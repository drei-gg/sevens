package gg.seven.vault

import android.os.SystemClock

/**
 * A system screen Vault itself opened (document or photo picker, SAF create/open, folder picker,
 * share chooser, camera permission prompt, biometric prompt) that stops Vault's activity while it
 * is in front. Leaving Vault any other way locks at once; while one of these is in flight the lock
 * waits, but never longer than [timeoutMillis] after the flow began, so a picker left open or a
 * person who walks away from it still ends with a locked vault.
 *
 * [begin] is called as the flow is launched, [end] when its result arrives or Vault returns to
 * the front (whichever comes first). Main thread only.
 */
class SystemFlowGuard(
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var startedAt: Long? = null

    fun begin() {
        startedAt = clock()
    }

    fun end() {
        startedAt = null
    }

    /** How long the lock may still wait for the flow in flight; 0 means lock now. */
    fun remaining(): Long {
        val started = startedAt ?: return 0
        val left = started + timeoutMillis - clock()
        if (left <= 0) startedAt = null
        return left.coerceAtLeast(0)
    }

    /** A flow is in flight and still within its bound. */
    val active: Boolean
        get() = remaining() > 0

    companion object {
        /** Long enough to browse to a file or pick a share target; short enough to bound exposure. */
        const val TIMEOUT_MILLIS = 2 * 60 * 1000L
    }
}
