package com.localvoicetv

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Call submit/cancel/close and deliver callbacks on one UI thread. */
class LatestRequestRunner(
    private val deliver: (() -> Unit) -> Unit,
    private val worker: ExecutorService = Executors.newFixedThreadPool(2),
) {
    private var generation = 0L
    private var closed = false
    private var cancellation: NetworkCancellation? = null
    private var future: Future<*>? = null

    fun <T> submit(task: (NetworkCancellation) -> T, completed: (Result<T>) -> Unit) {
        check(!closed) { "请求执行器已关闭" }
        cancel()
        val id = generation
        val token = NetworkCancellation().also { cancellation = it }
        future = worker.submit {
            val result = try {
                token.check()
                Result.success(task(token).also { token.check() })
            } catch (e: Exception) {
                Result.failure(e)
            }
            deliver {
                if (!closed && generation == id) {
                    cancellation = null
                    future = null
                    completed(result)
                }
            }
        }
    }

    fun cancel() {
        generation++
        future?.cancel(true)
        cancellation?.cancel()
        future = null
        cancellation = null
    }

    fun close() {
        closed = true
        cancel()
        worker.shutdownNow()
    }
}
