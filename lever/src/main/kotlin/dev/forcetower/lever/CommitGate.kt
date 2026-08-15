package dev.forcetower.lever

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The turnstile every commit's post-swap phase passes through: persist, log,
 * deliver (spec 0003 §4).
 *
 * It is never held together with the state lock, and it admits **strictly in
 * ticket order regardless of arrival order** — commit 2 reaching the gate first
 * waits for commit 1 — so disk order, log order, and update-delivery order all
 * equal activation order. Without that, a thread preempted between committing
 * version 2 and writing it can land after a version 3 write, and the next
 * launch restores version 2 over a process serving version 3: an ordering bug
 * nothing detects (spec 0002 §12.1).
 *
 * A failed persist still advances the ticket — a wedged queue must not follow a
 * full disk.
 */
internal class CommitGate {
    private val lock = ReentrantLock()
    private val turn = lock.newCondition()
    private var next = 1L

    fun admit(ticket: Long, body: () -> Unit) {
        // Uninterruptible on purpose: an interrupt in the middle of the queue
        // would let a later commit overtake this one, which is precisely what
        // the gate exists to prevent.
        lock.withLock { while (next != ticket) turn.awaitUninterruptibly() }
        try {
            body()
        } finally {
            lock.withLock {
                next = ticket + 1
                turn.signalAll()
            }
        }
    }

    /** Waits until every ticket up to and including [through] has passed. */
    fun awaitDrain(through: Long) {
        lock.withLock { while (next <= through) turn.awaitUninterruptibly() }
    }
}
