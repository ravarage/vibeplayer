package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CommandExecutorTest {
    @Test
    fun noOwnerOutsideACommandThread() {
        assertNull(CommandExecutor.ofCurrentThread())
    }

    /**
     * The B-08 known risk: a healthy but slow task on the abandoned executor returns after the new
     * executor took over. It must see that it is stale (PlayerEngine.isStaleCommandThread).
     */
    @Test(timeout = 5_000L)
    fun aSlowTaskOnAnAbandonedExecutorSeesItIsNoLongerCurrent() {
        val old = CommandExecutor("VP-Command-old") { 0L }
        val fresh = CommandExecutor("VP-Command-new") { 0L }
        val current = AtomicReference(old)
        val slowTaskRunning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val staleSeen = AtomicReference<Boolean?>(null)
        val freshOwner = AtomicReference<CommandExecutor?>(null)
        try {
            old.execute {
                slowTaskRunning.countDown()
                release.await()
                staleSeen.set(CommandExecutor.ofCurrentThread() !== current.get())
            }
            assertTrue(slowTaskRunning.await(2, TimeUnit.SECONDS))
            // Abandon: the new executor is published while the old task is still running.
            current.set(fresh)
            val freshRan = CountDownLatch(1)
            fresh.execute {
                freshOwner.set(CommandExecutor.ofCurrentThread())
                freshRan.countDown()
            }
            assertTrue(freshRan.await(2, TimeUnit.SECONDS))
            release.countDown()
            old.shutdown()
            assertTrue(old.awaitTermination(2, TimeUnit.SECONDS))
            assertSame(true, staleSeen.get())
            assertSame(fresh, freshOwner.get())
        } finally {
            release.countDown()
            old.shutdownNow()
            fresh.shutdownNow()
        }
    }

    @Test(timeout = 5_000L)
    fun abandoningClosesTheDescriptorOfADroppedReopenTask() {
        val executor = CommandExecutor("VP-Command-stuck") { 0L }
        val blocker = CountDownLatch(1)
        val started = CountDownLatch(1)
        val closed = mutableListOf<String>()
        try {
            executor.execute { started.countDown(); blocker.await() } // the stalled task
            assertTrue(started.await(2, TimeUnit.SECONDS))
            executor.execute { } // a plain queued command: dropped, nothing to close
            executor.execute(FdOwningTask("fdclose://42") { error("must not run") })
            val dropped = executor.shutdownNowClosingFds { closed.add(it) }
            assertEquals(2, dropped.size)
            assertEquals(listOf("fdclose://42"), closed)
        } finally {
            blocker.countDown()
            executor.shutdownNow()
        }
    }
}
