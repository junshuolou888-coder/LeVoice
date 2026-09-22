package com.localvoicetv

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LatestRequestRunnerTest {
    @Test fun alreadyQueuedOldResultCannotOverwriteNewQuery() {
        val queue = LinkedBlockingQueue<() -> Unit>()
        val runner = LatestRequestRunner({ queue.add(it) }, Executors.newSingleThreadExecutor())
        val results = mutableListOf<String>()
        try {
            runner.submit({ "old" }) { results += it.getOrThrow() }
            val oldCallback = requireNotNull(queue.poll(3, TimeUnit.SECONDS))
            runner.submit({ "new" }) { results += it.getOrThrow() }
            val newCallback = requireNotNull(queue.poll(3, TimeUnit.SECONDS))
            oldCallback(); newCallback()
            assertEquals(listOf("new"), results)
        } finally { runner.close() }
    }

    @Test fun closeDropsResultsAlreadyPostedToTheUi() {
        val queue = LinkedBlockingQueue<() -> Unit>()
        val runner = LatestRequestRunner({ queue.add(it) })
        var called = false
        runner.submit({ "result" }) { called = true }
        val callback = requireNotNull(queue.poll(3, TimeUnit.SECONDS))
        runner.close(); callback()
        assertFalse(called)
    }

    @Test fun cancellingAnActiveTaskDoesNotCallItsCompletionHandler() {
        val queue = LinkedBlockingQueue<() -> Unit>()
        val started = CountDownLatch(1)
        val runner = LatestRequestRunner({ queue.add(it) })
        var called = false
        try {
            runner.submit({ token -> started.countDown(); CountDownLatch(1).await(); token.check() }) { called = true }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            runner.cancel()
            requireNotNull(queue.poll(3, TimeUnit.SECONDS))()
            assertFalse(called)
        } finally { runner.close() }
    }
}
