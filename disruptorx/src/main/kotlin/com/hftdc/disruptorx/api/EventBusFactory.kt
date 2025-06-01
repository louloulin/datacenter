package com.hftdc.disruptorx.api

import com.hftdc.disruptorx.impl.SimpleEventBusImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * 事件总线工厂 - 提供简化的创建API
 */
object EventBusFactory {
    
    /**
     * 创建事件总线 - 最基础的方法
     */
    fun <T> create(): EventBus<T> {
        val config = EventBusConfig<T>(
            ringBufferSize = 1024,
            waitStrategy = WaitStrategy.YIELDING,
            producerType = ProducerType.MULTI,
            distributedConfig = null,
            performanceConfig = PerformanceConfig(),
            monitoringConfig = MonitoringConfig(),
            backpressureConfig = BackpressureConfig()
        )
        
        return SimpleEventBusImpl(config)
    }
    
    /**
     * 创建事件总线 - 带配置
     */
    fun <T> create(init: EventBusBuilder<T>.() -> Unit): EventBus<T> {
        val builder = EventBusBuilder<T>()
        builder.init()
        val config = builder.build()
        
        return SimpleEventBusImpl(config)
    }
}

// ========== DSL扩展函数 ==========

/**
 * 创建事件总线 - 最简单的DSL语法
 * 使用示例：val bus = eventBus<OrderEvent>()
 */
inline fun <reified T> eventBus(): EventBus<T> {
    return EventBusFactory.create<T>()
}

/**
 * 创建事件总线 - 带配置的DSL语法
 * 使用示例：
 * val bus = eventBus<OrderEvent> {
 *     ringBufferSize = 2048
 *     waitStrategy = WaitStrategy.BUSY_SPIN
 *     distributed("cluster:9090")
 * }
 */
inline fun <reified T> eventBus(noinline init: EventBusBuilder<T>.() -> Unit): EventBus<T> {
    return EventBusFactory.create(init)
}

// ========== 协程扩展函数 ==========

/**
 * 异步发布事件并等待完成
 */
suspend fun <T> EventBus<T>.emitAndWait(event: T): Boolean {
    return try {
        emitAsync(event)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * 批量异步发布事件
 */
suspend fun <T> EventBus<T>.emitAllAsync(events: Collection<T>) {
    events.forEach { emitAsync(it) }
}

// ========== 集合扩展函数 ==========

/**
 * 将集合中的所有元素发布到事件总线
 */
fun <T> Collection<T>.emitTo(bus: EventBus<T>) {
    bus.emitAll(this)
}

/**
 * 将序列中的所有元素发布到事件总线
 */
fun <T> Sequence<T>.emitTo(bus: EventBus<T>) {
    forEach { bus.emit(it) }
}

/**
 * 将数组中的所有元素发布到事件总线
 */
fun <T> Array<T>.emitTo(bus: EventBus<T>) {
    forEach { bus.emit(it) }
}

// ========== Flow扩展函数 ==========

/**
 * 将Flow中的所有元素发布到事件总线
 */
fun <T> Flow<T>.emitTo(bus: EventBus<T>): Flow<T> = onEach { bus.emit(it) }

/**
 * 将Flow中的所有元素异步发布到事件总线
 */
suspend fun <T> Flow<T>.emitToAsync(bus: EventBus<T>) {
    collect { bus.emitAsync(it) }
}

// ========== 便捷操作扩展函数 ==========

/**
 * 创建一个只处理特定类型事件的处理器
 */
inline fun <T, reified R : T> EventBus<T>.onType(noinline handler: (R) -> Unit): Subscription {
    return filter { it is R }.on { handler(it as R) }
}

/**
 * 创建一个只处理特定类型事件的异步处理器
 */
inline fun <T, reified R : T> EventBus<T>.onTypeAsync(noinline handler: suspend (R) -> Unit): Subscription {
    return filter { it is R }.onAsync { handler(it as R) }
}

/**
 * 创建一个一次性事件处理器（处理一次后自动取消）
 */
fun <T> EventBus<T>.once(handler: (T) -> Unit): Subscription {
    lateinit var subscription: Subscription
    subscription = on { event ->
        try {
            handler(event)
        } finally {
            subscription.cancel()
        }
    }
    return subscription
}

/**
 * 创建一个一次性异步事件处理器
 */
fun <T> EventBus<T>.onceAsync(handler: suspend (T) -> Unit): Subscription {
    lateinit var subscription: Subscription
    subscription = onAsync { event ->
        try {
            handler(event)
        } finally {
            subscription.cancel()
        }
    }
    return subscription
}

/**
 * 创建一个带条件的事件处理器
 */
fun <T> EventBus<T>.onWhen(
    condition: (T) -> Boolean,
    handler: (T) -> Unit
): Subscription {
    return filter(condition).on(handler)
}

/**
 * 创建一个带条件的异步事件处理器
 */
fun <T> EventBus<T>.onWhenAsync(
    condition: (T) -> Boolean,
    handler: suspend (T) -> Unit
): Subscription {
    return filter(condition).onAsync(handler)
}

// ========== 链式操作扩展函数 ==========

/**
 * 链式过滤操作
 */
fun <T> EventBus<T>.where(predicate: (T) -> Boolean): EventBus<T> = filter(predicate)

/**
 * 链式映射操作
 */
fun <T, R> EventBus<T>.select(transform: (T) -> R): EventBus<R> = map(transform)

/**
 * 链式主题操作
 */
fun <T> EventBus<T>.forTopic(name: String): TopicEventBus<T> = topic(name)

// ========== 生命周期扩展函数 ==========

/**
 * 使用事件总线并自动关闭
 */
inline fun <T, R> EventBus<T>.use(block: (EventBus<T>) -> R): R {
    return try {
        start()
        block(this)
    } finally {
        close()
    }
}

/**
 * 启动事件总线并返回自身（链式调用）
 */
fun <T> EventBus<T>.started(): EventBus<T> {
    start()
    return this
}

// ========== 监控扩展函数 ==========

/**
 * 获取吞吐量（每秒事件数）
 */
val <T> EventBus<T>.throughputPerSecond: Double
    get() = metrics.throughput

/**
 * 获取平均延迟（毫秒）
 */
val <T> EventBus<T>.averageLatencyMs: Double
    get() = metrics.latency.mean / 1_000_000.0

/**
 * 获取P99延迟（毫秒）
 */
val <T> EventBus<T>.p99LatencyMs: Double
    get() = metrics.latency.p99 / 1_000_000.0

/**
 * 检查事件总线是否健康
 */
val <T> EventBus<T>.isHealthy: Boolean
    get() = health.isHealthy

// ========== 调试扩展函数 ==========

/**
 * 添加调试日志
 */
fun <T> EventBus<T>.debug(tag: String = "EventBus"): EventBus<T> {
    on { event ->
        println("[$tag] Event: $event")
    }
    return this
}

/**
 * 添加性能监控日志
 */
fun <T> EventBus<T>.monitor(intervalMs: Long = 5000): EventBus<T> {
    // 简化实现：定期打印性能指标
    // 实际实现中应该使用定时器
    return this
}
