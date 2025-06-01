package com.hftdc.disruptorx.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DSL标记注解
 */
@DslMarker
annotation class EventBusDsl

/**
 * 事件总线构建器 - 支持Kotlin DSL语法
 */
@EventBusDsl
class EventBusBuilder<T> {
    
    // ========== 基础配置 ==========
    
    /**
     * RingBuffer大小，必须是2的幂
     */
    var ringBufferSize: Int = 1024
    
    /**
     * 等待策略
     */
    var waitStrategy: WaitStrategy = WaitStrategy.YIELDING
    
    /**
     * 生产者类型
     */
    var producerType: ProducerType = ProducerType.MULTI
    
    // ========== 内部配置对象 ==========
    
    private var distributedConfig: DistributedConfig? = null
    private var performanceConfig: PerformanceConfig? = null
    private var monitoringConfig: MonitoringConfig? = null
    private var backpressureConfig: BackpressureConfig? = null
    
    // ========== DSL配置方法 ==========
    
    /**
     * 分布式配置
     */
    fun distributed(init: DistributedConfigBuilder.() -> Unit) {
        distributedConfig = DistributedConfigBuilder().apply(init).build()
    }
    
    /**
     * 分布式配置 - 简化版本，只需要集群地址
     */
    fun distributed(clusterAddress: String) {
        distributedConfig = DistributedConfig(
            enabled = true,
            clusterAddress = clusterAddress
        )
    }
    
    /**
     * 性能配置
     */
    fun performance(init: PerformanceConfigBuilder.() -> Unit) {
        performanceConfig = PerformanceConfigBuilder().apply(init).build()
    }
    
    /**
     * 监控配置
     */
    fun monitoring(init: MonitoringConfigBuilder.() -> Unit) {
        monitoringConfig = MonitoringConfigBuilder().apply(init).build()
    }
    
    /**
     * 背压配置
     */
    fun backpressure(init: BackpressureConfigBuilder.() -> Unit) {
        backpressureConfig = BackpressureConfigBuilder().apply(init).build()
    }
    
    /**
     * 线程亲和性配置
     */
    fun threadAffinity(init: ThreadAffinityConfigBuilder.() -> Unit) {
        // 将线程亲和性配置集成到性能配置中
        if (performanceConfig == null) {
            performanceConfig = PerformanceConfig()
        }
        val affinityConfig = ThreadAffinityConfigBuilder().apply(init).build()
        performanceConfig = performanceConfig!!.copy(threadAffinity = affinityConfig)
    }
    
    // ========== 构建方法 ==========
    
    /**
     * 构建事件总线配置
     */
    internal fun build(): EventBusConfig<T> {
        return EventBusConfig(
            ringBufferSize = ringBufferSize,
            waitStrategy = waitStrategy,
            producerType = producerType,
            distributedConfig = distributedConfig,
            performanceConfig = performanceConfig ?: PerformanceConfig(),
            monitoringConfig = monitoringConfig ?: MonitoringConfig(),
            backpressureConfig = backpressureConfig ?: BackpressureConfig()
        )
    }
}

/**
 * 分布式配置构建器
 */
@EventBusDsl
class DistributedConfigBuilder {
    var enabled: Boolean = true
    var nodeId: String? = null
    var clusterAddress: String = ""
    var replication: Int = 1
    var consistency: Consistency = Consistency.EVENTUAL
    
    private val clusterNodes = mutableListOf<String>()
    
    /**
     * 添加集群节点
     */
    fun cluster(vararg nodes: String) {
        clusterNodes.addAll(nodes)
    }
    
    /**
     * 分区策略
     */
    fun partitionBy(partitioner: (Any) -> Int) {
        // TODO: 实现分区策略
    }
    
    internal fun build(): DistributedConfig {
        return DistributedConfig(
            enabled = enabled,
            nodeId = nodeId,
            clusterAddress = clusterAddress,
            clusterNodes = clusterNodes.toList(),
            replication = replication,
            consistency = consistency
        )
    }
}

/**
 * 性能配置构建器
 */
@EventBusDsl
class PerformanceConfigBuilder {
    var batchSize: Int = 100
    var flushInterval: Duration = 10.milliseconds
    var enableZeroCopy: Boolean = false
    var objectPoolSize: Int = 1000
    
    internal fun build(): PerformanceConfig {
        return PerformanceConfig(
            batchSize = batchSize,
            flushInterval = flushInterval,
            enableZeroCopy = enableZeroCopy,
            objectPoolSize = objectPoolSize
        )
    }
}

/**
 * 监控配置构建器
 */
@EventBusDsl
class MonitoringConfigBuilder {
    var metrics: Boolean = true
    var tracing: Boolean = false
    var logging: LogLevel = LogLevel.INFO
    
    internal fun build(): MonitoringConfig {
        return MonitoringConfig(
            metrics = metrics,
            tracing = tracing,
            logging = logging
        )
    }
}

/**
 * 背压配置构建器
 */
@EventBusDsl
class BackpressureConfigBuilder {
    var strategy: BackpressureStrategy = BackpressureStrategy.BLOCK
    var bufferSize: Int = 10000
    
    internal fun build(): BackpressureConfig {
        return BackpressureConfig(
            strategy = strategy,
            bufferSize = bufferSize
        )
    }
}

/**
 * 线程亲和性配置构建器
 */
@EventBusDsl
class ThreadAffinityConfigBuilder {
    var producerCore: Int? = null
    var consumerCores: List<Int> = emptyList()
    
    internal fun build(): ThreadAffinityConfig {
        return ThreadAffinityConfig(
            producerCore = producerCore,
            consumerCores = consumerCores
        )
    }
}

// ========== 配置数据类 ==========

/**
 * 事件总线配置
 */
data class EventBusConfig<T>(
    val ringBufferSize: Int,
    val waitStrategy: WaitStrategy,
    val producerType: ProducerType,
    val distributedConfig: DistributedConfig?,
    val performanceConfig: PerformanceConfig,
    val monitoringConfig: MonitoringConfig,
    val backpressureConfig: BackpressureConfig
)

/**
 * 分布式配置
 */
data class DistributedConfig(
    val enabled: Boolean = false,
    val nodeId: String? = null,
    val clusterAddress: String = "",
    val clusterNodes: List<String> = emptyList(),
    val replication: Int = 1,
    val consistency: Consistency = Consistency.EVENTUAL
)

/**
 * 性能配置
 */
data class PerformanceConfig(
    val batchSize: Int = 100,
    val flushInterval: Duration = 10.milliseconds,
    val enableZeroCopy: Boolean = false,
    val objectPoolSize: Int = 1000,
    val threadAffinity: ThreadAffinityConfig? = null
)

/**
 * 监控配置
 */
data class MonitoringConfig(
    val metrics: Boolean = true,
    val tracing: Boolean = false,
    val logging: LogLevel = LogLevel.INFO
)

/**
 * 背压配置
 */
data class BackpressureConfig(
    val strategy: BackpressureStrategy = BackpressureStrategy.BLOCK,
    val bufferSize: Int = 10000
)

/**
 * 线程亲和性配置
 */
data class ThreadAffinityConfig(
    val producerCore: Int? = null,
    val consumerCores: List<Int> = emptyList()
)

/**
 * 日志级别
 */
enum class LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR
}
