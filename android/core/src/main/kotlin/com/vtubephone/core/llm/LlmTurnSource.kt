package com.vtubephone.core.llm

import com.vtubephone.core.brain.*
import java.util.concurrent.*

/** One worker and one bounded pending slot; cancellation removes pending work, never spawning extra workers. */
internal fun requestExecutor(name: String) = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
    ArrayBlockingQueue(1), ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } })
    .apply { allowCoreThreadTimeOut(true) }

class LlmTurnSource(private val client: GeminiClient, private val fallback: TurnSource,
    private val timeoutMs: Long = 9000, private val minIntervalMs: Long = 1500,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) : TurnSource, AutoCloseable {
    private val executor = requestExecutor("gemini-request")
    private var lastRequestMs: Long? = null
    /** Why the last call fell back to the rule brain (null when Gemini answered). For logs only. */
    @Volatile var lastFallbackReason: String? = null; private set
    init { require(timeoutMs > 0 && minIntervalMs >= 0) }

    override fun next(ctx: BrainContext): AgentTurn? {
        lastFallbackReason = null
        if (ctx.trigger == Trigger.Start || (ctx.trigger as? Trigger.Timeout)?.turn?.options?.isEmpty() == true)
            return fallback.next(ctx).also { lastFallbackReason = "rule-only trigger" }
        val request = synchronized(this) {
            val now = clock()
            val limited = ctx.trigger !is Trigger.Voice && lastRequestMs?.let { now - it < minIntervalMs } == true
            if (limited) null else try {
                executor.submit<AgentTurn?> { client.generate(ctx) }.also { lastRequestMs = now }
            } catch (_: RejectedExecutionException) { null }
        } ?: return fallback.next(ctx).also { lastFallbackReason = "rate limited" }
        val result = try { request.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); lastFallbackReason = "interrupted"; null }
        catch (e: ExecutionException) { lastFallbackReason = "error: ${e.cause?.let { it::class.simpleName + " " + it.message }}"; null }
        catch (_: TimeoutException) { lastFallbackReason = "timeout ${timeoutMs}ms"; null }
        finally { request.cancel(true); executor.remove(request as Runnable) }
        if (result == null && lastFallbackReason == null) lastFallbackReason = "invalid or empty response"
        return result ?: fallback.next(ctx)
    }

    override fun close() { executor.shutdownNow() }
}
