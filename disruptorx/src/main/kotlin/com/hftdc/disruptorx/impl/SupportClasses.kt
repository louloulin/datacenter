package com.hftdc.disruptorx.impl

import com.hftdc.disruptorx.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * 过滤事件总线实现
 */
class FilteredEventBus<T>(
    private val parentBus: EventBus<T>,
    private val predicate: (T) -> Boolean
) : EventBus<T> by parentBus {
    
    override fun on(handler: (T) -> Unit): Subscription {
        return parentBus.on { event ->
            if (predicate(event)) {
                handler(event)
            }
        }
    }
    
    override fun onAsync(handler: suspend (T) -> Unit): Subscription {
        return parentBus.onAsync { event ->
            if (predicate(event)) {
                handler(event)
            }
        }
    }
}

/**
 * 映射事件总线实现
 */
class MappedEventBus<T, R>(
    private val parentBus: EventBus<T>,
    private val transform: (T) -> R
) : EventBus<R> {
    
    override fun emit(event: R) {
        throw UnsupportedOperationException("Cannot emit to mapped event bus")
    }
    
    override fun emitAll(events: Collection<R>) {
        throw UnsupportedOperationException("Cannot emit to mapped event bus")
    }
    
    override suspend fun emitAsync(event: R) {
        throw UnsupportedOperationException("Cannot emit to mapped event bus")
    }
    
    override fun on(handler: (R) -> Unit): Subscription {
        return parentBus.on { event ->
            try {
                val transformed = transform(event)
                handler(transformed)
            } catch (e: Exception) {
                println("Error transforming event: ${e.message}")
            }
        }
    }
    
    override fun onAsync(handler: suspend (R) -> Unit): Subscription {
        return parentBus.onAsync { event ->
            try {
                val transformed = transform(event)
                handler(transformed)
            } catch (e: Exception) {
                println("Error transforming event: ${e.message}")
            }
        }
    }
    
    // 简化实现 - 其他方法抛出异常或委托
    override fun topic(name: String): TopicEventBus<R> {
        throw UnsupportedOperationException("Topics not supported for mapped event bus")
    }
    
    override fun filter(predicate: (R) -> Boolean): EventBus<R> {
        throw UnsupportedOperationException("Filter not supported for mapped event bus")
    }
    
    override fun <S> map(transform: (R) -> S): EventBus<S> {
        throw UnsupportedOperationException("Map not supported for mapped event bus")
    }
    
    override fun asFlow(): Flow<R> {
        throw UnsupportedOperationException("Flow not supported for mapped event bus")
    }
    
    override fun flow(): Flow<R> = asFlow()
    
    override fun batch(size: Int, timeout: Duration, handler: (List<R>) -> Unit) {
        throw UnsupportedOperationException("Batch not supported for mapped event bus")
    }
    
    override fun parallel(concurrency: Int, handler: (R) -> Unit) {
        throw UnsupportedOperationException("Parallel not supported for mapped event bus")
    }
    
    override fun pipeline(init: PipelineBuilder<R>.() -> Unit) {
        throw UnsupportedOperationException("Pipeline not supported for mapped event bus")
    }
    
    override fun start() = parentBus.start()
    override fun stop() = parentBus.stop()
    override fun close() = parentBus.close()
    
    override val metrics: EventBusMetrics get() = parentBus.metrics
    override val health: HealthStatus get() = parentBus.health
}

/**
 * 批处理收集器
 */
class BatchCollector<T>(
    private val batchSize: Int,
    private val timeout: Duration,
    private val handler: (List<T>) -> Unit
) {
    
    private val batch = mutableListOf<T>()
    private var lastFlushTime = System.currentTimeMillis()
    
    @Synchronized
    fun add(event: T) {
        batch.add(event)
        
        val now = System.currentTimeMillis()
        val shouldFlush = batch.size >= batchSize || 
                         (now - lastFlushTime) >= timeout.inWholeMilliseconds
        
        if (shouldFlush) {
            flush()
        }
    }
    
    @Synchronized
    private fun flush() {
        if (batch.isNotEmpty()) {
            val batchCopy = batch.toList()
            batch.clear()
            lastFlushTime = System.currentTimeMillis()
            
            try {
                handler(batchCopy)
            } catch (e: Exception) {
                println("Error processing batch: ${e.message}")
            }
        }
    }
}

/**
 * 管道构建器实现
 */
class PipelineBuilderImpl<T> : PipelineBuilder<T> {
    
    private val stages = mutableListOf<PipelineStage<T>>()
    
    override fun stage(name: String, handler: (T) -> T) {
        stages.add(SyncPipelineStage(name, handler))
    }
    
    override fun stageAsync(name: String, handler: suspend (T) -> T) {
        stages.add(AsyncPipelineStage(name, handler))
    }
    
    suspend fun process(event: T): T {
        var currentEvent = event
        
        for (stage in stages) {
            currentEvent = when (stage) {
                is SyncPipelineStage -> stage.handler(currentEvent)
                is AsyncPipelineStage -> stage.handler(currentEvent)
            }
        }
        
        return currentEvent
    }
}

/**
 * 管道阶段接口
 */
sealed class PipelineStage<T>(val name: String)

/**
 * 同步管道阶段
 */
class SyncPipelineStage<T>(
    name: String,
    val handler: (T) -> T
) : PipelineStage<T>(name)

/**
 * 异步管道阶段
 */
class AsyncPipelineStage<T>(
    name: String,
    val handler: suspend (T) -> T
) : PipelineStage<T>(name)

/**
 * 性能指标实现
 */
class EventBusMetricsImpl : EventBusMetrics {
    
    private val eventCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val latencySum = AtomicLong(0)
    private val latencyCount = AtomicLong(0)
    private val recentLatencies = ConcurrentLinkedQueue<Long>()
    private val maxRecentLatencies = 1000
    
    override val throughput: Double
        get() {
            // 简化实现：返回最近的事件计数
            return eventCount.get().toDouble()
        }
    
    override val latency: LatencyStats
        get() {
            val latencies = recentLatencies.toList().sorted()
            if (latencies.isEmpty()) {
                return LatencyStats(0.0, 0.0, 0.0, 0.0, 0.0)
            }
            
            val size = latencies.size
            return LatencyStats(
                mean = latencies.average(),
                p50 = latencies[size * 50 / 100].toDouble(),
                p95 = latencies[size * 95 / 100].toDouble(),
                p99 = latencies[size * 99 / 100].toDouble(),
                p999 = latencies[size * 999 / 1000].toDouble()
            )
        }
    
    override val errorRate: Double
        get() {
            val total = eventCount.get()
            val errors = errorCount.get()
            return if (total > 0) errors.toDouble() / total else 0.0
        }
    
    override val queueSize: Long
        get() = 0 // 简化实现
    
    fun recordEvent(latencyNanos: Long) {
        eventCount.incrementAndGet()
        latencySum.addAndGet(latencyNanos)
        latencyCount.incrementAndGet()
        
        // 保持最近的延迟记录
        recentLatencies.offer(latencyNanos)
        while (recentLatencies.size > maxRecentLatencies) {
            recentLatencies.poll()
        }
    }
    
    fun recordError() {
        errorCount.incrementAndGet()
    }
}

/**
 * 健康状态实现
 */
class HealthStatusImpl(
    override val isHealthy: Boolean,
    override val status: String,
    override val details: Map<String, Any>
) : HealthStatus
