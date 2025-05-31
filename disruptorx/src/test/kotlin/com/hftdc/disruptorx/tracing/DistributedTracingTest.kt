package com.hftdc.disruptorx.tracing

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class DistributedTracingTest {

    private lateinit var tracing: DistributedTracing
    private lateinit var collector: InMemoryTracingCollector
    private val nodeId = "test-node-1"

    @BeforeEach
    fun setup() {
        collector = InMemoryTracingCollector()
        tracing = DistributedTracing(nodeId, collector)
    }

    @Test
    fun `test start trace creates valid context`() {
        val context = tracing.startTrace("test-operation")
        
        assertNotNull(context)
        assertEquals("test-operation", context.operation)
        assertTrue(context.traceId.contains(nodeId))
        assertTrue(context.spanId.contains(nodeId))
        assertTrue(context.startTime > 0)
        assertNull(context.parentSpanId)
    }

    @Test
    fun `test add span creates child span`() {
        val context = tracing.startTrace("parent-operation")
        val span = tracing.addSpan(context, "child-operation")
        
        assertNotNull(span)
        assertEquals(context.traceId, span.traceId)
        assertEquals(context.spanId, span.parentSpanId)
        assertEquals("child-operation", span.operation)
        assertTrue(span.spanId.contains(nodeId))
        assertTrue(span.startTime > 0)
        assertNull(span.endTime)
    }

    @Test
    fun `test span tags and logs`() {
        val context = tracing.startTrace("test-operation")
        val span = tracing.addSpan(context, "test-span")
        
        span.addTag("key1", "value1")
        span.addTag("key2", "value2")
        span.addLog("Test log message")
        
        assertEquals("value1", span.tags["key1"])
        assertEquals("value2", span.tags["key2"])
        assertEquals(nodeId, span.tags["node.id"])
        assertEquals("test-span", span.tags["node.operation"])
        
        assertEquals(1, span.logs.size)
        assertEquals("Test log message", span.logs[0].message)
        assertTrue(span.logs[0].timestamp > 0)
    }

    @Test
    fun `test finish span calculates duration`() = runBlocking {
        val context = tracing.startTrace("test-operation")
        val span = tracing.addSpan(context, "test-span")
        
        delay(10) // 等待一小段时间
        
        tracing.finishSpan(span)
        
        assertNotNull(span.endTime)
        assertTrue(span.endTime!! > span.startTime)
        
        val duration = span.getDuration()
        assertNotNull(duration)
        assertTrue(duration!!.inWholeNanoseconds > 0)
    }

    @Test
    fun `test tracing collector receives spans`() = runBlocking {
        val context = tracing.startTrace("test-operation")
        val span1 = tracing.addSpan(context, "span1")
        val span2 = tracing.addSpan(context, "span2")
        
        tracing.finishSpan(span1)
        tracing.finishSpan(span2)
        
        val traces = collector.getTrace(context.traceId)
        assertEquals(2, traces.size)
        
        val spanIds = traces.map { it.spanId }.toSet()
        assertTrue(spanIds.contains(span1.spanId))
        assertTrue(spanIds.contains(span2.spanId))
    }

    @Test
    fun `test traced decorator handles success`() {
        var executionCount = 0
        
        val result = traced(tracing, "test-operation") { span ->
            executionCount++
            span.addTag("test", "value")
            "success"
        }
        
        assertEquals("success", result)
        assertEquals(1, executionCount)
        
        // 验证span被收集
        val allTraces = collector.getAllTraces()
        assertEquals(1, allTraces.size)
        
        val spans = allTraces.values.first()
        assertEquals(1, spans.size)
        assertEquals("test-operation", spans[0].operation)
        assertEquals("value", spans[0].tags["test"])
    }

    @Test
    fun `test traced decorator handles exception`() {
        var executionCount = 0
        
        assertThrows(RuntimeException::class.java) {
            traced(tracing, "test-operation") { span ->
                executionCount++
                span.addTag("test", "value")
                throw RuntimeException("Test exception")
            }
        }
        
        assertEquals(1, executionCount)
        
        // 验证span被收集且包含错误信息
        val allTraces = collector.getAllTraces()
        assertEquals(1, allTraces.size)
        
        val spans = allTraces.values.first()
        assertEquals(1, spans.size)
        val span = spans[0]
        assertEquals("test-operation", span.operation)
        assertEquals("true", span.tags["error"])
        assertEquals("Test exception", span.tags["error.message"])
        assertTrue(span.logs.any { it.message.contains("RuntimeException") })
    }

    @Test
    fun `test tracedAsync decorator`() = runBlocking {
        var executionCount = 0
        
        val result = tracedAsync(tracing, "async-operation") { span ->
            executionCount++
            span.addTag("async", "true")
            delay(5)
            "async-success"
        }
        
        assertEquals("async-success", result)
        assertEquals(1, executionCount)
        
        // 验证span被收集
        val allTraces = collector.getAllTraces()
        assertEquals(1, allTraces.size)
        
        val spans = allTraces.values.first()
        assertEquals(1, spans.size)
        assertEquals("async-operation", spans[0].operation)
        assertEquals("true", spans[0].tags["async"])
    }

    @Test
    fun `test active span management`() {
        val context = tracing.startTrace("test-operation")
        val span1 = tracing.addSpan(context, "span1")
        val span2 = tracing.addSpan(context, "span2")
        
        // 验证活跃span
        val activeSpans = tracing.getAllActiveSpans()
        assertEquals(2, activeSpans.size)
        
        val activeSpan1 = tracing.getActiveSpan(span1.spanId)
        assertNotNull(activeSpan1)
        assertEquals(span1.spanId, activeSpan1!!.spanId)
        
        // 完成一个span
        runBlocking { tracing.finishSpan(span1) }
        
        val remainingSpans = tracing.getAllActiveSpans()
        assertEquals(1, remainingSpans.size)
        assertEquals(span2.spanId, remainingSpans[0].spanId)
        
        assertNull(tracing.getActiveSpan(span1.spanId))
    }

    @Test
    fun `test cleanup expired spans`() = runBlocking {
        val context = tracing.startTrace("test-operation")
        val span = tracing.addSpan(context, "test-span")

        // 验证span是活跃的
        assertEquals(1, tracing.getAllActiveSpans().size)

        // 等待一小段时间让span变"老"
        delay(10)

        // 清理过期span（使用很短的过期时间）
        tracing.cleanupExpiredSpans(1.milliseconds)

        // 等待清理完成
        delay(10)

        // 验证span被清理
        assertEquals(0, tracing.getAllActiveSpans().size)

        // 验证span被收集到collector
        val traces = collector.getTrace(context.traceId)
        assertEquals(1, traces.size)
        assertTrue(traces[0].logs.any { it.message.contains("expired") })
    }

    @Test
    fun `test thread local context`() {
        val context = tracing.startTrace("test-operation")
        
        // 验证当前上下文
        val currentContext = DistributedTracing.getCurrentContext()
        assertNotNull(currentContext)
        assertEquals(context.traceId, currentContext!!.traceId)
        
        // 清除上下文
        DistributedTracing.setCurrentContext(null)
        assertNull(DistributedTracing.getCurrentContext())
        
        // 重新设置上下文
        DistributedTracing.setCurrentContext(context)
        val restoredContext = DistributedTracing.getCurrentContext()
        assertNotNull(restoredContext)
        assertEquals(context.traceId, restoredContext!!.traceId)
    }

    @Test
    fun `test baggage in trace context`() {
        val context = tracing.startTrace("test-operation")
        
        context.addBaggage("user_id", "12345")
        context.addBaggage("session_id", "abcdef")
        
        assertEquals("12345", context.getBaggage("user_id"))
        assertEquals("abcdef", context.getBaggage("session_id"))
        assertNull(context.getBaggage("non_existent"))
        
        assertEquals(2, context.baggage.size)
    }

    @Test
    fun `test collector flush`() = runBlocking {
        val context = tracing.startTrace("test-operation")
        val span = tracing.addSpan(context, "test-span")
        
        tracing.finishSpan(span)
        tracing.flush()
        
        // flush操作应该成功完成（内存收集器不需要实际刷新）
        val traces = collector.getTrace(context.traceId)
        assertEquals(1, traces.size)
    }

    @Test
    fun `test collector clear`() = runBlocking {
        val context = tracing.startTrace("test-operation")
        val span = tracing.addSpan(context, "test-span")
        
        tracing.finishSpan(span)
        
        // 验证有数据
        assertEquals(1, collector.getAllTraces().size)
        
        // 清除数据
        collector.clear()
        
        // 验证数据被清除
        assertEquals(0, collector.getAllTraces().size)
        assertTrue(collector.getTrace(context.traceId).isEmpty())
    }
}
