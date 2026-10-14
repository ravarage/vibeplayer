package com.vibeplayer.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

class BoundedCallTest {
    @Test(timeout = 5_000L)
    fun returnsNullWhenTheCommandThreadIsBlocked() {
        val clock = AtomicLong(1_000L)
        val executor = CommandExecutor("VP-Command-test", clock::get)
        val blocker = CountDownLatch(1)
        try {
            executor.execute { blocker.await() } // a decoder stall: never returns on its own
            val start = System.nanoTime()
            val v = runBlocking { BoundedCall.onExecutor(executor, 300L) { 42L } }
            val tookMs = (System.nanoTime() - start) / 1_000_000
            assertNull(v)
            assertTrue("took $tookMs ms", tookMs < 2_000L)
            clock.set(1_000L + StallPolicy.STUCK_COMMAND_MS)
            assertEquals(StallPolicy.STUCK_COMMAND_MS, executor.busyForMs())
        } finally {
            blocker.countDown()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000L)
    fun returnsTheValueWhenTheThreadAnswers() {
        val executor = CommandExecutor("VP-Command-test", System::currentTimeMillis)
        try {
            assertEquals(42L, runBlocking { BoundedCall.onExecutor(executor, 1_000L) { 42L } })
            assertNull(runBlocking { BoundedCall.onExecutor<Long>(executor, 1_000L) { error("mpv gone") } })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000L)
    fun returnsNullWhenTheExecutorIsShutDown() {
        val executor = CommandExecutor("VP-Command-test", System::currentTimeMillis)
        executor.shutdownNow()
        assertNull(runBlocking { BoundedCall.onExecutor(executor, 1_000L) { 1L } })
    }
}
