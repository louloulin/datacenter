package com.hftdc.disruptorx.tracing

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * 分布式追踪上下文
 */
data class TraceContext(
    val traceId: String,
    val spanId: String,
    val operation: String,
    val startTime: Long,
    val baggage: MutableMap<String, String> = mutableMapOf(),
    var parentSpanId: String? = null
) {
    fun addBaggage(key: String, value: String) {
        baggage[key] = value
    }
    
    fun getBaggage(key: String): String? = baggage[key]
}

/**
 * Span上下文
 */
data class SpanContext(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val operation: String,
    val startTime: Long,
    var endTime: Long? = null,
    val tags: MutableMap<String, String> = mutableMapOf(),
    val logs: MutableList<LogEntry> = mutableListOf()
) {
    fun addTag(key: String, value: String) {
        tags[key] = value
    }
    
    fun addLog(message: String, timestamp: Long = System.nanoTime()) {
        logs.add(LogEntry(timestamp, message))
    }
    
    fun finish() {
        endTime = System.nanoTime()
    }
    
    fun getDuration(): Duration? {
        return endTime?.let { (it - startTime).nanoseconds }
    }
}

/**
 * 日志条目
 */
data class LogEntry(
    val timestamp: Long,
    val message: String
)

/**
 * 追踪收集器接口
 */
interface TracingCollector {
    suspend fun collect(span: SpanContext)
    suspend fun flush()
}

/**
 * 内存追踪收集器
 */
class InMemoryTracingCollector : TracingCollector {
    private val spans = ConcurrentHashMap<String, MutableList<SpanContext>>()
    
    override suspend fun collect(span: SpanContext) {
        spans.computeIfAbsent(span.traceId) { mutableListOf() }.add(span)
    }
    
    override suspend fun flush() {
        // 内存收集器不需要刷新
    }
    
    fun getTrace(traceId: String): List<SpanContext> {
        return spans[traceId]?.toList() ?: emptyList()
    }
    
    fun getAllTraces(): Map<String, List<SpanContext>> {
        return spans.mapValues { it.value.toList() }
    }
    
    fun clear() {
        spans.clear()
    }
}

/**
 * 分布式追踪系统
 */
class DistributedTracing(
    private val nodeId: String,
    private val tracingCollector: TracingCollector = InMemoryTracingCollector()
) {
    private val traceIdGenerator = AtomicLong(0)
    private val spanIdGenerator = AtomicLong(0)
    private val activeSpans = ConcurrentHashMap<String, SpanContext>()
    
    companion object {
        private val threadLocalContext = ThreadLocal<TraceContext?>()
        
        fun getCurrentContext(): TraceContext? = threadLocalContext.get()
        fun setCurrentContext(context: TraceContext?) = threadLocalContext.set(context)
    }
    
    /**
     * 开始新的追踪
     */
    fun startTrace(operation: String): TraceContext {
        val traceId = generateTraceId()
        val spanId = generateSpanId()
        
        val context = TraceContext(
            traceId = traceId,
            spanId = spanId,
            operation = operation,
            startTime = System.nanoTime()
        )
        
        setCurrentContext(context)
        return context
    }
    
    /**
     * 添加子Span
     */
    fun addSpan(context: TraceContext, operation: String): SpanContext {
        val span = SpanContext(
            traceId = context.traceId,
            spanId = generateSpanId(),
            parentSpanId = context.spanId,
            operation = operation,
            startTime = System.nanoTime()
        )
        
        // 添加节点信息
        span.addTag("node.id", nodeId)
        span.addTag("node.operation", operation)
        
        activeSpans[span.spanId] = span
        return span
    }
    
    /**
     * 完成Span
     */
    suspend fun finishSpan(span: SpanContext) {
        span.finish()
        activeSpans.remove(span.spanId)
        
        // 异步发送到追踪收集器
        tracingCollector.collect(span)
    }
    
    /**
     * 添加标签到当前Span
     */
    fun addTag(key: String, value: String) {
        getCurrentContext()?.let { context ->
            activeSpans[context.spanId]?.addTag(key, value)
        }
    }
    
    /**
     * 添加日志到当前Span
     */
    fun addLog(message: String) {
        getCurrentContext()?.let { context ->
            activeSpans[context.spanId]?.addLog(message)
        }
    }
    
    /**
     * 获取活跃的Span
     */
    fun getActiveSpan(spanId: String): SpanContext? {
        return activeSpans[spanId]
    }
    
    /**
     * 获取所有活跃的Span
     */
    fun getAllActiveSpans(): List<SpanContext> {
        return activeSpans.values.toList()
    }
    
    /**
     * 清理过期的Span
     */
    suspend fun cleanupExpiredSpans(maxAge: Duration = Duration.parse("5m")) {
        val now = System.nanoTime()
        val expiredSpans = activeSpans.values.filter { 
            (now - it.startTime).nanoseconds > maxAge 
        }
        
        expiredSpans.forEach { span ->
            span.addLog("Span expired and auto-finished")
            finishSpan(span)
        }
    }
    
    /**
     * 刷新追踪数据
     */
    suspend fun flush() {
        tracingCollector.flush()
    }
    
    private fun generateTraceId(): String {
        return "${nodeId}-${System.currentTimeMillis()}-${traceIdGenerator.incrementAndGet()}"
    }
    
    private fun generateSpanId(): String {
        return "${nodeId}-${spanIdGenerator.incrementAndGet()}"
    }
}

/**
 * 追踪装饰器 - 用于自动追踪方法调用
 */
inline fun <T> traced(
    tracing: DistributedTracing,
    operation: String,
    crossinline block: (SpanContext) -> T
): T {
    val context = DistributedTracing.getCurrentContext()
        ?: tracing.startTrace(operation)
    
    val span = tracing.addSpan(context, operation)
    return try {
        val result = block(span)
        runBlocking { tracing.finishSpan(span) }
        result
    } catch (e: Exception) {
        span.addTag("error", "true")
        span.addTag("error.message", e.message ?: "Unknown error")
        span.addLog("Exception: ${e.javaClass.simpleName}: ${e.message}")
        runBlocking { tracing.finishSpan(span) }
        throw e
    }
}

/**
 * 异步追踪装饰器
 */
suspend inline fun <T> tracedAsync(
    tracing: DistributedTracing,
    operation: String,
    crossinline block: suspend (SpanContext) -> T
): T {
    val context = DistributedTracing.getCurrentContext()
        ?: tracing.startTrace(operation)
    
    val span = tracing.addSpan(context, operation)
    return try {
        val result = block(span)
        tracing.finishSpan(span)
        result
    } catch (e: Exception) {
        span.addTag("error", "true")
        span.addTag("error.message", e.message ?: "Unknown error")
        span.addLog("Exception: ${e.javaClass.simpleName}: ${e.message}")
        tracing.finishSpan(span)
        throw e
    }
}
