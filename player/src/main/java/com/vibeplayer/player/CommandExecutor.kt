package com.vibeplayer.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The engine's single command thread. Records since when the running task has been busy, so a
 * thread stuck inside a blocked mpv call (B-08) can be told apart from a queue that is just busy.
 */
internal class CommandExecutor(
    threadName: String,
    private val clock: () -> Long
) : ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), { runnable ->
    Thread(runnable, threadName).apply { isDaemon = true }
}) {
    /** Clock time at which the running task started, 0 when idle. */
    @Volatile var busySinceMs: Long = 0L
        private set

    override fun beforeExecute(t: Thread?, r: Runnable?) {
        owner.set(this)
        busySinceMs = clock()
        super.beforeExecute(t, r)
    }

    override fun afterExecute(r: Runnable?, t: Throwable?) {
        super.afterExecute(r, t)
        busySinceMs = 0L
    }

    /** How long the running task has been running, 0 when idle. */
    fun busyForMs(): Long = busySinceMs.let { if (it == 0L) 0L else (clock() - it).coerceAtLeast(0L) }

    /**
     * Stops the executor and drops its queue like [shutdownNow]; the descriptor of every dropped
     * [FdOwningTask] is handed to [close], since that task can no longer pass it to mpv or close it.
     */
    fun shutdownNowClosingFds(close: (fdPath: String) -> Unit): List<Runnable> =
        shutdownNow().also { dropped -> dropped.forEach { (it as? FdOwningTask)?.let { t -> close(t.fdPath) } } }

    companion object {
        private val owner = ThreadLocal<CommandExecutor?>()

        /** The executor whose task is running on the calling thread, null on any other thread. */
        fun ofCurrentThread(): CommandExecutor? = owner.get()
    }
}

/** A command-thread task that owns the descriptor of [fdPath] (an `fdclose://N` path) until it runs. */
internal class FdOwningTask(val fdPath: String, private val body: () -> Unit) : Runnable {
    override fun run() = body()
}

internal object BoundedCall {
    /**
     * Runs [block] on [executor] and waits at most [timeoutMs] for it. Returns null on timeout,
     * rejection or failure. Unlike `withTimeout { withContext(executorDispatcher) { ... } }` this
     * returns even when the executor's thread is blocked forever: the waiting side only awaits a
     * deferred, it never needs the blocked thread to run a coroutine to completion.
     */
    suspend fun <T : Any> onExecutor(executor: Executor, timeoutMs: Long, block: () -> T?): T? {
        val result = CompletableDeferred<T?>()
        try {
            executor.execute {
                try {
                    result.complete(block())
                } catch (t: Throwable) {
                    result.complete(null)
                }
            }
        } catch (_: RejectedExecutionException) {
            return null
        }
        return withTimeoutOrNull(timeoutMs) { result.await() }
    }
}
