package com.hftdc.disruptorx.api

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * 简化的事件总线接口 - 基于api.md设计
 * 提供极简的API，支持从3行代码启动到复杂分布式配置的渐进式设计
 */
interface EventBus<T> {
    
    // ========== 基础操作 - Level 1 ==========
    
    /**
     * 发布事件 - 最基础的操作
     * @param event 要发布的事件
     */
    fun emit(event: T)
    
    /**
     * 批量发布事件
     * @param events 要发布的事件集合
     */
    fun emitAll(events: Collection<T>)
    
    /**
     * 异步发布事件 - 协程友好
     * @param event 要发布的事件
     */
    suspend fun emitAsync(event: T)
    
    /**
     * 注册事件处理器 - 最基础的订阅
     * @param handler 事件处理函数
     * @return 订阅对象，用于取消订阅
     */
    fun on(handler: (T) -> Unit): Subscription
    
    /**
     * 注册异步事件处理器 - 协程友好
     * @param handler 异步事件处理函数
     * @return 订阅对象，用于取消订阅
     */
    fun onAsync(handler: suspend (T) -> Unit): Subscription
    
    // ========== 主题操作 - Level 2 ==========
    
    /**
     * 获取主题事件总线
     * @param name 主题名称
     * @return 主题专用的事件总线
     */
    fun topic(name: String): TopicEventBus<T>
    
    // ========== 过滤和转换 - Level 2 ==========
    
    /**
     * 创建过滤后的事件总线
     * @param predicate 过滤条件
     * @return 过滤后的事件总线
     */
    fun filter(predicate: (T) -> Boolean): EventBus<T>
    
    /**
     * 创建转换后的事件总线
     * @param transform 转换函数
     * @return 转换后的事件总线
     */
    fun <R> map(transform: (T) -> R): EventBus<R>
    
    // ========== 流式操作 - Level 3 ==========
    
    /**
     * 转换为Kotlin Flow
     * @return 事件流
     */
    fun asFlow(): Flow<T>
    
    /**
     * 获取事件流（带背压处理）
     * @return 事件流
     */
    fun flow(): Flow<T>
    
    // ========== 批处理 - Level 3 ==========
    
    /**
     * 批处理事件
     * @param size 批次大小
     * @param timeout 超时时间
     * @param handler 批处理函数
     */
    fun batch(size: Int, timeout: Duration, handler: (List<T>) -> Unit)
    
    // ========== 并行处理 - Level 3 ==========
    
    /**
     * 并行处理事件
     * @param concurrency 并发度
     * @param handler 处理函数
     */
    fun parallel(concurrency: Int, handler: (T) -> Unit)
    
    // ========== 管道处理 - Level 3 ==========
    
    /**
     * 创建处理管道
     * @param init 管道配置
     */
    fun pipeline(init: PipelineBuilder<T>.() -> Unit)
    
    // ========== 生命周期管理 ==========
    
    /**
     * 启动事件总线
     */
    fun start()
    
    /**
     * 停止事件总线
     */
    fun stop()
    
    /**
     * 关闭事件总线并释放资源
     */
    fun close()
    
    // ========== 监控和状态 ==========
    
    /**
     * 获取性能指标
     */
    val metrics: EventBusMetrics
    
    /**
     * 获取健康状态
     */
    val health: HealthStatus
}

/**
 * 主题事件总线接口
 */
interface TopicEventBus<T> {
    /**
     * 发布事件到主题
     */
    fun emit(event: T)
    
    /**
     * 异步发布事件到主题
     */
    suspend fun emitAsync(event: T)
    
    /**
     * 订阅主题事件
     */
    fun on(handler: (T) -> Unit): Subscription
    
    /**
     * 异步订阅主题事件
     */
    fun onAsync(handler: suspend (T) -> Unit): Subscription
}

/**
 * 订阅接口
 */
interface Subscription {
    /**
     * 取消订阅
     */
    fun cancel()
    
    /**
     * 是否已取消
     */
    val isCancelled: Boolean
}

/**
 * 管道构建器
 */
interface PipelineBuilder<T> {
    /**
     * 添加处理阶段
     * @param name 阶段名称
     * @param handler 处理函数
     */
    fun stage(name: String, handler: (T) -> T)
    
    /**
     * 添加异步处理阶段
     * @param name 阶段名称
     * @param handler 异步处理函数
     */
    fun stageAsync(name: String, handler: suspend (T) -> T)
}

/**
 * 性能指标
 */
interface EventBusMetrics {
    /**
     * 吞吐量 (events/sec)
     */
    val throughput: Double
    
    /**
     * 延迟统计
     */
    val latency: LatencyStats
    
    /**
     * 错误率
     */
    val errorRate: Double
    
    /**
     * 当前队列大小
     */
    val queueSize: Long
}

/**
 * 延迟统计
 */
data class LatencyStats(
    val mean: Double,
    val p50: Double,
    val p95: Double,
    val p99: Double,
    val p999: Double
)

/**
 * 健康状态
 */
interface HealthStatus {
    /**
     * 是否健康
     */
    val isHealthy: Boolean
    
    /**
     * 状态描述
     */
    val status: String
    
    /**
     * 详细信息
     */
    val details: Map<String, Any>
}

/**
 * 等待策略枚举
 */
enum class WaitStrategy {
    BLOCKING,
    YIELDING,
    BUSY_SPIN,
    SLEEPING
}

/**
 * 生产者类型枚举
 */
enum class ProducerType {
    SINGLE,
    MULTI
}

/**
 * 一致性级别枚举
 */
enum class Consistency {
    EVENTUAL,
    STRONG,
    QUORUM
}

/**
 * 背压策略枚举
 */
enum class BackpressureStrategy {
    BLOCK,
    DROP_OLDEST,
    DROP_LATEST,
    ERROR
}
