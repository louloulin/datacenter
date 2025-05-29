package com.hftdc.disruptorx.monitoring

import com.hftdc.disruptorx.api.NodeInfo
import com.hftdc.disruptorx.distributed.DistributedLoadBalancer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder
import kotlin.time.Duration.Companion.seconds

/**
 * 分布式 Disruptor 指标收集器
 * 负责收集和聚合分布式环境下的性能指标
 */
class DistributedDisruptorMetrics(
    private val nodeId: String,
    private val loadBalancer: DistributedLoadBalancer
) {
    
    // 本地指标收集器
    private val localMetrics = MetricsCollector()
    
    // 分布式指标存储
    private val distributedMetrics = ConcurrentHashMap<String, NodeMetrics>()
    
    // 指标同步
    private val metricsSyncChannel = Channel<MetricsSync>(Channel.UNLIMITED)
    private val syncMutex = Mutex()
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 指标聚合器
    private val aggregator = MetricsAggregator()
    
    // 运行状态标志
    @Volatile
    private var isRunning = true
    
    init {
        // 启动指标同步协程
        scope.launch {
            startMetricsSync()
        }
        
        // 启动指标聚合协程
        scope.launch {
            startMetricsAggregation()
        }
    }
    
    /**
     * 记录吞吐量指标
     */
    fun recordThroughput(eventsPerSecond: Double, tags: Map<String, String> = emptyMap()) {
        localMetrics.setGauge("disruptor.throughput", eventsPerSecond, tags + ("node" to nodeId))
        
        // 发送到分布式同步
        metricsSyncChannel.trySend(
            MetricsSync(
                nodeId = nodeId,
                metricName = "disruptor.throughput",
                value = eventsPerSecond,
                timestamp = System.currentTimeMillis(),
                tags = tags
            )
        )
    }
    
    /**
     * 记录延迟指标
     */
    suspend fun recordLatency(latencyNanos: Long, tags: Map<String, String> = emptyMap()) {
        val latencyMs = latencyNanos / 1_000_000.0
        localMetrics.recordHistogram("disruptor.latency", latencyMs, tags + ("node" to nodeId))
        
        metricsSyncChannel.trySend(
            MetricsSync(
                nodeId = nodeId,
                metricName = "disruptor.latency",
                value = latencyMs,
                timestamp = System.currentTimeMillis(),
                tags = tags
            )
        )
    }
    
    /**
     * 记录队列深度
     */
    fun recordQueueDepth(depth: Int, ringBufferName: String = "default") {
        val tags = mapOf("node" to nodeId, "ring_buffer" to ringBufferName)
        localMetrics.setGauge("disruptor.queue_depth", depth.toDouble(), tags)
        
        // 更新负载均衡器的队列深度信息
        scope.launch {
            loadBalancer.updateNodeLoad(
                nodeId = nodeId,
                cpuUsage = getCurrentCpuUsage(),
                memoryUsage = getCurrentMemoryUsage(),
                queueDepth = depth
            )
        }
        
        metricsSyncChannel.trySend(
            MetricsSync(
                nodeId = nodeId,
                metricName = "disruptor.queue_depth",
                value = depth.toDouble(),
                timestamp = System.currentTimeMillis(),
                tags = mapOf("ring_buffer" to ringBufferName)
            )
        )
    }
    
    /**
     * 记录消费者处理速率
     */
    fun recordConsumerRate(consumerId: String, eventsPerSecond: Double) {
        val tags = mapOf("node" to nodeId, "consumer" to consumerId)
        localMetrics.setGauge("disruptor.consumer_rate", eventsPerSecond, tags)
        
        metricsSyncChannel.trySend(
            MetricsSync(
                nodeId = nodeId,
                metricName = "disruptor.consumer_rate",
                value = eventsPerSecond,
                timestamp = System.currentTimeMillis(),
                tags = mapOf("consumer" to consumerId)
            )
        )
    }
    
    /**
     * 记录生产者发布速率
     */
    fun recordProducerRate(producerId: String, eventsPerSecond: Double) {
        val tags = mapOf("node" to nodeId, "producer" to producerId)
        localMetrics.setGauge("disruptor.producer_rate", eventsPerSecond, tags)
        
        metricsSyncChannel.trySend(
            MetricsSync(
                nodeId = nodeId,
                metricName = "disruptor.producer_rate",
                value = eventsPerSecond,
                timestamp = System.currentTimeMillis(),
                tags = mapOf("producer" to producerId)
            )
        )
    }
    
    /**
     * 记录分布式事件传输延迟
     */
    suspend fun recordNetworkLatency(targetNodeId: String, latencyNanos: Long) {
        val latencyMs = latencyNanos / 1_000_000.0
        val tags = mapOf(
            "source_node" to nodeId,
            "target_node" to targetNodeId
        )
        
        localMetrics.recordHistogram("disruptor.network_latency", latencyMs, tags)
        
        metricsSyncChannel.trySend(
            MetricsSync(
                nodeId = nodeId,
                metricName = "disruptor.network_latency",
                value = latencyMs,
                timestamp = System.currentTimeMillis(),
                tags = mapOf("target_node" to targetNodeId)
            )
        )
    }
    
    /**
     * 获取集群级别的聚合指标
     */
    suspend fun getClusterMetrics(): ClusterMetrics {
        return syncMutex.withLock {
            aggregator.aggregateClusterMetrics(distributedMetrics.values.toList())
        }
    }
    
    /**
     * 获取节点级别的指标
     */
    fun getNodeMetrics(nodeId: String): NodeMetrics? {
        return distributedMetrics[nodeId]
    }
    
    /**
     * 获取本地节点指标
     */
    suspend fun getLocalMetrics(): NodeMetrics {
        val latencyStats = localMetrics.getHistogramStats("disruptor.latency")
        return NodeMetrics(
            nodeId = nodeId,
            throughput = localMetrics.getGauge("disruptor.throughput"),
            averageLatency = latencyStats?.mean ?: 0.0,
            p99Latency = latencyStats?.p99 ?: 0.0,
            queueDepth = localMetrics.getGauge("disruptor.queue_depth").toInt(),
            cpuUsage = getCurrentCpuUsage(),
            memoryUsage = getCurrentMemoryUsage(),
            lastUpdateTime = System.currentTimeMillis()
        )
    }
    
    /**
     * 启动指标同步
     */
    private suspend fun startMetricsSync() {
        while (isRunning) {
            try {
                val sync = metricsSyncChannel.receive()
                processMetricsSync(sync)
            } catch (e: Exception) {
                if (isRunning) {
                    // 只在运行状态下记录错误
                    println("Error in metrics sync: ${e.message}")
                } else {
                    // 如果已经停止运行，退出循环
                    break
                }
            }
        }
    }
    
    /**
     * 处理指标同步
     */
    private suspend fun processMetricsSync(sync: MetricsSync) {
        syncMutex.withLock {
            val nodeMetrics = distributedMetrics.getOrPut(sync.nodeId) {
                NodeMetrics(
                    nodeId = sync.nodeId,
                    throughput = 0.0,
                    averageLatency = 0.0,
                    p99Latency = 0.0,
                    queueDepth = 0,
                    cpuUsage = 0.0,
                    memoryUsage = 0.0,
                    lastUpdateTime = System.currentTimeMillis()
                )
            }
            
            // 更新对应的指标
            val updatedMetrics = when (sync.metricName) {
                "disruptor.throughput" -> nodeMetrics.copy(
                    throughput = sync.value,
                    lastUpdateTime = sync.timestamp
                )
                "disruptor.latency" -> nodeMetrics.copy(
                    averageLatency = sync.value,
                    lastUpdateTime = sync.timestamp
                )
                "disruptor.queue_depth" -> nodeMetrics.copy(
                    queueDepth = sync.value.toInt(),
                    lastUpdateTime = sync.timestamp
                )
                else -> nodeMetrics.copy(lastUpdateTime = sync.timestamp)
            }
            
            distributedMetrics[sync.nodeId] = updatedMetrics
        }
    }
    
    /**
     * 启动指标聚合
     */
    private suspend fun startMetricsAggregation() {
        while (isRunning) {
            delay(5.seconds)
            
            try {
                // 清理过期的指标数据
                cleanupExpiredMetrics()
                
                // 触发指标聚合
                aggregator.triggerAggregation()
            } catch (e: Exception) {
                if (isRunning) {
                    println("Error in metrics aggregation: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 清理过期的指标数据
     */
    private suspend fun cleanupExpiredMetrics() {
        val currentTime = System.currentTimeMillis()
        val expirationTime = 60000L // 60秒
        
        syncMutex.withLock {
            distributedMetrics.entries.removeIf { (_, metrics) ->
                currentTime - metrics.lastUpdateTime > expirationTime
            }
        }
    }
    
    /**
     * 获取当前CPU使用率
     */
    private fun getCurrentCpuUsage(): Double {
        return try {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            if (osBean is com.sun.management.OperatingSystemMXBean) {
                osBean.processCpuLoad.takeIf { it >= 0.0 } ?: 0.0
            } else {
                0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }
    
    /**
     * 获取当前内存使用率
     */
    private fun getCurrentMemoryUsage(): Double {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()
        
        return if (maxMemory > 0) {
            usedMemory.toDouble() / maxMemory.toDouble()
        } else {
            0.0
        }
    }
    
    /**
     * 关闭指标收集器
     */
    fun shutdown() {
        isRunning = false
        metricsSyncChannel.close()
        scope.cancel()
        localMetrics.shutdown()
    }
}

/**
 * 指标同步数据
 */
data class MetricsSync(
    val nodeId: String,
    val metricName: String,
    val value: Double,
    val timestamp: Long,
    val tags: Map<String, String> = emptyMap()
)

/**
 * 节点指标
 */
data class NodeMetrics(
    val nodeId: String,
    val throughput: Double,        // 吞吐量 (events/sec)
    val averageLatency: Double,    // 平均延迟 (ms)
    val p99Latency: Double,        // P99延迟 (ms)
    val queueDepth: Int,           // 队列深度
    val cpuUsage: Double,          // CPU使用率
    val memoryUsage: Double,       // 内存使用率
    val lastUpdateTime: Long       // 最后更新时间
)

/**
 * 集群指标
 */
data class ClusterMetrics(
    val totalNodes: Int,
    val activeNodes: Int,
    val totalThroughput: Double,
    val averageLatency: Double,
    val maxLatency: Double,
    val totalQueueDepth: Int,
    val averageCpuUsage: Double,
    val averageMemoryUsage: Double,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 指标聚合器
 */
class MetricsAggregator {
    
    fun aggregateClusterMetrics(nodeMetrics: List<NodeMetrics>): ClusterMetrics {
        if (nodeMetrics.isEmpty()) {
            return ClusterMetrics(
                totalNodes = 0,
                activeNodes = 0,
                totalThroughput = 0.0,
                averageLatency = 0.0,
                maxLatency = 0.0,
                totalQueueDepth = 0,
                averageCpuUsage = 0.0,
                averageMemoryUsage = 0.0
            )
        }
        
        val currentTime = System.currentTimeMillis()
        val activeThreshold = 30000L // 30秒内有更新的节点认为是活跃的
        
        val activeMetrics = nodeMetrics.filter { 
            currentTime - it.lastUpdateTime < activeThreshold 
        }
        
        return ClusterMetrics(
            totalNodes = nodeMetrics.size,
            activeNodes = activeMetrics.size,
            totalThroughput = activeMetrics.sumOf { it.throughput },
            averageLatency = if (activeMetrics.isNotEmpty()) {
                activeMetrics.map { it.averageLatency }.average()
            } else 0.0,
            maxLatency = activeMetrics.maxOfOrNull { it.p99Latency } ?: 0.0,
            totalQueueDepth = activeMetrics.sumOf { it.queueDepth },
            averageCpuUsage = if (activeMetrics.isNotEmpty()) {
                activeMetrics.map { it.cpuUsage }.average()
            } else 0.0,
            averageMemoryUsage = if (activeMetrics.isNotEmpty()) {
                activeMetrics.map { it.memoryUsage }.average()
            } else 0.0
        )
    }
    
    fun triggerAggregation() {
        // 触发聚合逻辑，可以在这里添加更复杂的聚合策略
    }
}