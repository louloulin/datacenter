package com.hftdc.disruptorx.impl

import com.hftdc.disruptorx.api.*
import com.hftdc.disruptorx.core.RingBufferWrapper
import com.hftdc.disruptorx.core.EventProcessorWrapper
import com.hftdc.disruptorx.api.EventHandler as DisruptorXEventHandler
import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.WaitStrategy as LmaxWaitStrategy
import com.lmax.disruptor.BlockingWaitStrategy
import com.lmax.disruptor.YieldingWaitStrategy
import com.lmax.disruptor.BusySpinWaitStrategy
import com.lmax.disruptor.SleepingWaitStrategy
import com.lmax.disruptor.dsl.ProducerType as LmaxProducerType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * 简化的EventBus实现 - 专注于核心功能
 */
class SimpleEventBusImpl<T>(
    private val config: EventBusConfig<T>
) : EventBus<T>, CoroutineScope {
    
    private val job = SupervisorJob()
    override val coroutineContext = Dispatchers.Default + job
    
    // 核心组件
    private val ringBuffer: RingBufferWrapper<EventWrapper<T>>
    private val eventProcessor: EventProcessorWrapper<EventWrapper<T>>
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r).apply {
            isDaemon = true
            name = "simple-eventbus-${System.nanoTime()}"
        }
    }
    
    // 订阅管理
    private val subscriptions = CopyOnWriteArrayList<EventSubscription<T>>()
    
    // 状态管理
    private val started = AtomicBoolean(false)
    private val eventCounter = AtomicLong(0)
    private val errorCounter = AtomicLong(0)
    
    // 性能指标
    private val metricsImpl = SimpleEventBusMetrics()
    
    init {
        // 创建RingBuffer
        val waitStrategy = convertWaitStrategy(config.waitStrategy)
        val producerType = convertProducerType(config.producerType)
        
        ringBuffer = RingBufferWrapper.create(
            factory = EventWrapperFactory<T>(),
            bufferSize = config.ringBufferSize,
            producerType = producerType,
            waitStrategy = waitStrategy
        )
        
        // 创建事件处理器
        val eventHandler = object : DisruptorXEventHandler<EventWrapper<T>> {
            override fun onEvent(wrapper: EventWrapper<T>, sequence: Long, endOfBatch: Boolean) {
                val event = wrapper.event ?: return
                
                // 处理所有订阅
                subscriptions.forEach { subscription ->
                    if (!subscription.isCancelled) {
                        try {
                            if (subscription.handler != null) {
                                subscription.handler.invoke(event)
                            } else if (subscription.asyncHandler != null) {
                                launch {
                                    subscription.asyncHandler.invoke(event)
                                }
                            }
                        } catch (e: Exception) {
                            errorCounter.incrementAndGet()
                            metricsImpl.recordError()
                        }
                    }
                }
            }
        }
        
        val barrier = ringBuffer.newBarrier()
        eventProcessor = EventProcessorWrapper.createBatchProcessor(
            dataProvider = ringBuffer.unwrap(),
            sequenceBarrier = barrier,
            eventHandler = eventHandler,
            executor = executor
        )
    }
    
    // ========== 基础操作实现 ==========
    
    override fun emit(event: T) {
        if (!started.get()) {
            throw IllegalStateException("EventBus not started")
        }
        
        val startTime = System.nanoTime()
        
        ringBuffer.publish { sequence, wrapper ->
            wrapper.event = event
            wrapper.timestamp = System.nanoTime()
            wrapper.sequence = sequence
        }
        
        eventCounter.incrementAndGet()
        metricsImpl.recordEvent(System.nanoTime() - startTime)
    }
    
    override fun emitAll(events: Collection<T>) {
        events.forEach { emit(it) }
    }
    
    override suspend fun emitAsync(event: T) = withContext(Dispatchers.IO) {
        emit(event)
    }
    
    override fun on(handler: (T) -> Unit): Subscription {
        val subscription = EventSubscription(
            id = generateSubscriptionId(),
            handler = handler,
            asyncHandler = null
        )
        
        subscriptions.add(subscription)
        return subscription
    }
    
    override fun onAsync(handler: suspend (T) -> Unit): Subscription {
        val subscription = EventSubscription(
            id = generateSubscriptionId(),
            handler = null,
            asyncHandler = handler
        )
        
        subscriptions.add(subscription)
        return subscription
    }
    
    // ========== 简化实现 - 其他方法 ==========
    
    override fun topic(name: String): TopicEventBus<T> {
        return SimpleTopicEventBus(this, name)
    }
    
    override fun filter(predicate: (T) -> Boolean): EventBus<T> {
        return FilteredEventBus(this, predicate)
    }
    
    override fun <R> map(transform: (T) -> R): EventBus<R> {
        return MappedEventBus(this, transform)
    }
    
    override fun asFlow(): Flow<T> = flow {
        val subscription = on { event ->
            runBlocking { emit(event) }
        }
        
        try {
            awaitCancellation()
        } finally {
            subscription.cancel()
        }
    }
    
    override fun flow(): Flow<T> = asFlow()
    
    override fun batch(size: Int, timeout: Duration, handler: (List<T>) -> Unit) {
        val batchCollector = BatchCollector(size, timeout, handler)
        on { event -> batchCollector.add(event) }
    }
    
    override fun parallel(concurrency: Int, handler: (T) -> Unit) {
        repeat(concurrency) {
            on { event ->
                launch {
                    try {
                        handler(event)
                    } catch (e: Exception) {
                        errorCounter.incrementAndGet()
                        metricsImpl.recordError()
                    }
                }
            }
        }
    }
    
    override fun pipeline(init: PipelineBuilder<T>.() -> Unit) {
        val builder = PipelineBuilderImpl<T>()
        builder.init()
        
        on { event ->
            launch {
                try {
                    builder.process(event)
                } catch (e: Exception) {
                    errorCounter.incrementAndGet()
                    metricsImpl.recordError()
                }
            }
        }
    }
    
    // ========== 生命周期管理 ==========
    
    override fun start() {
        if (started.compareAndSet(false, true)) {
            eventProcessor.start()
        }
    }
    
    override fun stop() {
        if (started.compareAndSet(true, false)) {
            eventProcessor.stop()
        }
    }
    
    override fun close() {
        stop()
        job.cancel()
        executor.shutdown()
    }
    
    // ========== 监控和状态 ==========
    
    override val metrics: EventBusMetrics get() = metricsImpl
    
    override val health: HealthStatus get() = SimpleHealthStatus(
        isHealthy = started.get() && eventProcessor.isRunning(),
        status = if (started.get()) "RUNNING" else "STOPPED",
        details = mapOf(
            "eventCount" to eventCounter.get(),
            "errorCount" to errorCounter.get(),
            "subscriptionCount" to subscriptions.size
        )
    )
    
    // ========== 内部方法 ==========
    
    private fun generateSubscriptionId(): String = "sub-${System.nanoTime()}"
    
    private fun convertWaitStrategy(strategy: WaitStrategy): LmaxWaitStrategy {
        return when (strategy) {
            WaitStrategy.BLOCKING -> BlockingWaitStrategy()
            WaitStrategy.YIELDING -> YieldingWaitStrategy()
            WaitStrategy.BUSY_SPIN -> BusySpinWaitStrategy()
            WaitStrategy.SLEEPING -> SleepingWaitStrategy()
        }
    }
    
    private fun convertProducerType(type: ProducerType): LmaxProducerType {
        return when (type) {
            ProducerType.SINGLE -> LmaxProducerType.SINGLE
            ProducerType.MULTI -> LmaxProducerType.MULTI
        }
    }
}

/**
 * 简化的主题事件总线
 */
class SimpleTopicEventBus<T>(
    private val parentBus: EventBus<T>,
    private val topicName: String
) : TopicEventBus<T> {
    
    override fun emit(event: T) {
        parentBus.emit(event)
    }
    
    override suspend fun emitAsync(event: T) {
        parentBus.emitAsync(event)
    }
    
    override fun on(handler: (T) -> Unit): Subscription {
        return parentBus.on(handler)
    }
    
    override fun onAsync(handler: suspend (T) -> Unit): Subscription {
        return parentBus.onAsync(handler)
    }
}

/**
 * 简化的性能指标实现
 */
class SimpleEventBusMetrics : EventBusMetrics {
    
    private val eventCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    
    override val throughput: Double
        get() = eventCount.get().toDouble()
    
    override val latency: LatencyStats
        get() = LatencyStats(0.0, 0.0, 0.0, 0.0, 0.0)
    
    override val errorRate: Double
        get() {
            val total = eventCount.get()
            val errors = errorCount.get()
            return if (total > 0) errors.toDouble() / total else 0.0
        }
    
    override val queueSize: Long
        get() = 0
    
    fun recordEvent(latencyNanos: Long) {
        eventCount.incrementAndGet()
    }
    
    fun recordError() {
        errorCount.incrementAndGet()
    }
}

/**
 * 简化的健康状态实现
 */
class SimpleHealthStatus(
    override val isHealthy: Boolean,
    override val status: String,
    override val details: Map<String, Any>
) : HealthStatus
