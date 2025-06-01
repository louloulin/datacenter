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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * 事件包装器 - 用于在RingBuffer中存储事件
 */
data class EventWrapper<T>(
    var event: T? = null,
    var timestamp: Long = 0,
    var sequence: Long = 0
) {
    fun reset() {
        event = null
        timestamp = 0
        sequence = 0
    }
}

/**
 * 事件包装器工厂
 */
class EventWrapperFactory<T> : EventFactory<EventWrapper<T>> {
    override fun newInstance(): EventWrapper<T> = EventWrapper()
}

/**
 * 基础EventBus实现 - 基于现有的DisruptorX核心组件
 */
class EventBusImpl<T>(
    private val config: EventBusConfig<T>
) : EventBus<T>, CoroutineScope {
    
    private val job = SupervisorJob()
    override val coroutineContext = Dispatchers.Default + job
    
    // 核心组件
    private val ringBuffer: RingBufferWrapper<EventWrapper<T>>
    private val eventProcessors = mutableListOf<EventProcessorWrapper<EventWrapper<T>>>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r).apply {
            isDaemon = true
            name = "eventbus-${System.nanoTime()}"
        }
    }
    
    // 订阅管理
    private val subscriptions = ConcurrentHashMap<String, MutableList<EventSubscription<T>>>()
    private val topicBuses = ConcurrentHashMap<String, TopicEventBusImpl<T>>()
    
    // 状态管理
    private val started = AtomicBoolean(false)
    private val eventCounter = AtomicLong(0)
    private val errorCounter = AtomicLong(0)
    
    // 性能指标
    private val metricsImpl = EventBusMetricsImpl()
    
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
        
        addSubscription("default", subscription)
        return subscription
    }
    
    override fun onAsync(handler: suspend (T) -> Unit): Subscription {
        val subscription = EventSubscription(
            id = generateSubscriptionId(),
            handler = null,
            asyncHandler = handler
        )
        
        addSubscription("default", subscription)
        return subscription
    }
    
    // ========== 主题操作实现 ==========
    
    override fun topic(name: String): TopicEventBus<T> {
        return topicBuses.computeIfAbsent(name) { 
            TopicEventBusImpl(this, name)
        }
    }
    
    // ========== 过滤和转换实现 ==========
    
    override fun filter(predicate: (T) -> Boolean): EventBus<T> {
        return FilteredEventBus(this, predicate)
    }
    
    override fun <R> map(transform: (T) -> R): EventBus<R> {
        return MappedEventBus(this, transform)
    }
    
    // ========== 流式操作实现 ==========
    
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
    
    // ========== 批处理实现 ==========
    
    override fun batch(size: Int, timeout: Duration, handler: (List<T>) -> Unit) {
        val batchCollector = BatchCollector(size, timeout, handler)
        on { event -> batchCollector.add(event) }
    }
    
    // ========== 并行处理实现 ==========
    
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
    
    // ========== 管道处理实现 ==========
    
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
            // 创建默认事件处理器
            createDefaultEventProcessor()
            
            // 启动所有处理器
            eventProcessors.forEach { it.start() }
        }
    }
    
    override fun stop() {
        if (started.compareAndSet(true, false)) {
            eventProcessors.forEach { it.stop() }
        }
    }
    
    override fun close() {
        stop()
        job.cancel()
        executor.shutdown()
    }
    
    // ========== 监控和状态 ==========
    
    override val metrics: EventBusMetrics get() = metricsImpl
    
    override val health: HealthStatus get() = HealthStatusImpl(
        isHealthy = started.get() && eventProcessors.all { it.isRunning() },
        status = if (started.get()) "RUNNING" else "STOPPED",
        details = mapOf(
            "eventCount" to eventCounter.get(),
            "errorCount" to errorCounter.get(),
            "processorCount" to eventProcessors.size
        )
    )
    
    // ========== 内部方法 ==========
    
    private fun addSubscription(topic: String, subscription: EventSubscription<T>) {
        val topicSubscriptions = subscriptions.computeIfAbsent(topic) { mutableListOf() }
        synchronized(topicSubscriptions) {
            topicSubscriptions.add(subscription)
        }
        
        // 如果已经启动，需要重新创建处理器
        if (started.get()) {
            createDefaultEventProcessor()
        }
    }
    
    private fun createDefaultEventProcessor() {
        // 创建事件处理器来处理所有订阅
        val eventHandler = object : DisruptorXEventHandler<EventWrapper<T>> {
            override fun onEvent(wrapper: EventWrapper<T>, sequence: Long, endOfBatch: Boolean) {
                val event = wrapper.event ?: return

                // 只处理默认主题的订阅
                processSubscriptions("default", event)
            }
        }
        
        val barrier = ringBuffer.newBarrier()
        val processor = EventProcessorWrapper.createBatchProcessor(
            dataProvider = ringBuffer.unwrap(),
            sequenceBarrier = barrier,
            eventHandler = eventHandler,
            executor = executor
        )
        
        eventProcessors.clear()
        eventProcessors.add(processor)
        
        if (started.get()) {
            processor.start()
        }
    }
    
    private fun processSubscriptions(topic: String, event: T) {
        val topicSubscriptions = subscriptions[topic] ?: return
        
        synchronized(topicSubscriptions) {
            topicSubscriptions.removeAll { it.isCancelled }
            
            topicSubscriptions.forEach { subscription ->
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

// ========== 支持类实现 ==========

/**
 * 事件订阅实现
 */
class EventSubscription<T>(
    val id: String,
    val handler: ((T) -> Unit)?,
    val asyncHandler: (suspend (T) -> Unit)?
) : Subscription {

    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
        cancelled.set(true)
    }

    override val isCancelled: Boolean get() = cancelled.get()
}

/**
 * 主题事件总线实现
 */
class TopicEventBusImpl<T>(
    private val parentBus: EventBusImpl<T>,
    private val topicName: String
) : TopicEventBus<T> {

    private val subscriptions = mutableListOf<EventSubscription<T>>()

    override fun emit(event: T) {
        parentBus.emit(event)
    }

    override suspend fun emitAsync(event: T) {
        parentBus.emitAsync(event)
    }

    override fun on(handler: (T) -> Unit): Subscription {
        val subscription = EventSubscription(
            id = "topic-${topicName}-${System.nanoTime()}",
            handler = handler,
            asyncHandler = null
        )

        synchronized(subscriptions) {
            subscriptions.add(subscription)
        }

        return subscription
    }

    override fun onAsync(handler: suspend (T) -> Unit): Subscription {
        val subscription = EventSubscription(
            id = "topic-${topicName}-${System.nanoTime()}",
            handler = null,
            asyncHandler = handler
        )

        synchronized(subscriptions) {
            subscriptions.add(subscription)
        }

        return subscription
    }

    internal fun processEvent(event: T) {
        synchronized(subscriptions) {
            subscriptions.removeAll { it.isCancelled }

            subscriptions.forEach { subscription ->
                try {
                    if (subscription.handler != null) {
                        subscription.handler.invoke(event)
                    } else if (subscription.asyncHandler != null) {
                        CoroutineScope(Dispatchers.Default).launch {
                            subscription.asyncHandler.invoke(event)
                        }
                    }
                } catch (e: Exception) {
                    // 错误处理
                    println("Error processing event in topic $topicName: ${e.message}")
                }
            }
        }
    }
}
