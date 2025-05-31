package com.hftdc.disruptorx.monitoring

import com.hftdc.disruptorx.security.SecurityManager
import com.hftdc.disruptorx.security.Permission
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 实时监控指标
 */
@Serializable
data class RealTimeMetrics(
    val timestamp: Long,
    val nodeId: String,
    val systemMetrics: SystemMetrics,
    val performanceMetrics: PerformanceMetrics,
    val businessMetrics: BusinessMetrics,
    val alertMetrics: AlertMetrics
)

/**
 * 系统指标
 */
@Serializable
data class SystemMetrics(
    val cpuUsage: Double,
    val memoryUsage: Double,
    val diskUsage: Double,
    val networkIn: Long,
    val networkOut: Long,
    val activeConnections: Int,
    val threadCount: Int
)

/**
 * 性能指标
 */
@Serializable
data class PerformanceMetrics(
    val throughput: Double,
    val averageLatency: Double,
    val p95Latency: Double,
    val p99Latency: Double,
    val errorRate: Double,
    val queueDepth: Int,
    val processingRate: Double
)

/**
 * 业务指标
 */
@Serializable
data class BusinessMetrics(
    val totalOrders: Long,
    val executedTrades: Long,
    val tradingVolume: Double,
    val activeUsers: Int,
    val rejectedOrders: Long,
    val averageOrderValue: Double
)

/**
 * 告警指标
 */
@Serializable
data class AlertMetrics(
    val criticalAlerts: Int,
    val warningAlerts: Int,
    val infoAlerts: Int,
    val lastAlertTime: Long,
    val alertRate: Double
)

/**
 * 监控仪表板配置
 */
data class DashboardConfig(
    val updateInterval: Duration = 5.seconds,
    val historyRetention: Duration = Duration.parse("1h"),
    val alertThresholds: AlertThresholds = AlertThresholds()
)

/**
 * 告警阈值配置
 */
data class AlertThresholds(
    val cpuUsageThreshold: Double = 80.0,
    val memoryUsageThreshold: Double = 85.0,
    val latencyThreshold: Double = 1000.0, // 微秒
    val errorRateThreshold: Double = 5.0,   // 百分比
    val throughputThreshold: Double = 1000.0 // 每秒
)

/**
 * 监控事件
 */
sealed class MonitoringEvent {
    data class MetricsUpdated(val metrics: RealTimeMetrics) : MonitoringEvent()
    data class AlertTriggered(val alert: MonitoringAlert) : MonitoringEvent()
    data class ThresholdExceeded(val metric: String, val value: Double, val threshold: Double) : MonitoringEvent()
}

/**
 * 监控告警信息
 */
@Serializable
data class MonitoringAlert(
    val id: String,
    val level: AlertLevel,
    val title: String,
    val message: String,
    val timestamp: Long,
    val nodeId: String,
    val metric: String,
    val value: Double,
    val threshold: Double
)

/**
 * 告警级别
 */
@Serializable
enum class AlertLevel {
    INFO,
    WARNING,
    CRITICAL
}

/**
 * 监控事件监听器
 */
interface MonitoringEventListener {
    suspend fun onEvent(event: MonitoringEvent)
}

/**
 * 实时监控仪表板
 */
class RealTimeMonitoringDashboard(
    private val nodeId: String,
    private val metricsCollector: TradingMetricsCollector,
    private val securityManager: SecurityManager,
    private val config: DashboardConfig = DashboardConfig()
) {
    
    private val listeners = mutableListOf<MonitoringEventListener>()
    private val metricsHistory = ConcurrentHashMap<Long, RealTimeMetrics>()
    private val activeAlerts = ConcurrentHashMap<String, MonitoringAlert>()
    private val alertIdGenerator = AtomicLong(0)
    
    private var monitoringJob: Job? = null
    private val json = Json { prettyPrint = true }
    
    // 性能统计
    private val latencyHistory = mutableListOf<Double>()
    private val throughputHistory = mutableListOf<Double>()
    private val errorCount = AtomicLong(0)
    private val requestCount = AtomicLong(0)
    
    /**
     * 启动监控仪表板
     */
    fun start() {
        monitoringJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                try {
                    collectAndPublishMetrics()
                    delay(config.updateInterval)
                } catch (e: Exception) {
                    println("监控数据收集异常: ${e.message}")
                }
            }
        }
        
        // 启动历史数据清理任务
        CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(Duration.parse("10m"))
                cleanupOldMetrics()
            }
        }
        
        println("实时监控仪表板已启动")
    }
    
    /**
     * 停止监控仪表板
     */
    fun stop() {
        monitoringJob?.cancel()
        println("实时监控仪表板已停止")
    }
    
    /**
     * 添加监控事件监听器
     */
    fun addListener(listener: MonitoringEventListener) {
        listeners.add(listener)
    }
    
    /**
     * 移除监控事件监听器
     */
    fun removeListener(listener: MonitoringEventListener) {
        listeners.remove(listener)
    }
    
    /**
     * 获取当前实时指标
     */
    suspend fun getCurrentMetrics(userToken: String): RealTimeMetrics? {
        // 验证权限
        if (!securityManager.hasPermission(userToken, Permission.READ_EVENTS)) {
            throw SecurityException("用户没有查看监控指标的权限")
        }
        
        return collectCurrentMetrics()
    }
    
    /**
     * 获取历史指标
     */
    suspend fun getHistoricalMetrics(
        userToken: String,
        startTime: Long,
        endTime: Long
    ): List<RealTimeMetrics> {
        // 验证权限
        if (!securityManager.hasPermission(userToken, Permission.READ_EVENTS)) {
            throw SecurityException("用户没有查看历史指标的权限")
        }
        
        return metricsHistory.values
            .filter { it.timestamp in startTime..endTime }
            .sortedBy { it.timestamp }
    }
    
    /**
     * 获取活跃告警
     */
    suspend fun getActiveAlerts(userToken: String): List<MonitoringAlert> {
        // 验证权限
        if (!securityManager.hasPermission(userToken, Permission.READ_EVENTS)) {
            throw SecurityException("用户没有查看告警的权限")
        }
        
        return activeAlerts.values.sortedByDescending { it.timestamp }
    }
    
    /**
     * 确认告警
     */
    suspend fun acknowledgeAlert(alertId: String, userToken: String): Boolean {
        // 验证权限
        if (!securityManager.hasPermission(userToken, Permission.WRITE_EVENTS)) {
            throw SecurityException("用户没有确认告警的权限")
        }
        
        val alert = activeAlerts.remove(alertId)
        return alert != null
    }
    
    /**
     * 获取监控仪表板状态
     */
    fun getDashboardStatus(): Map<String, Any> {
        return mapOf(
            "nodeId" to nodeId,
            "isRunning" to (monitoringJob?.isActive == true),
            "metricsCount" to metricsHistory.size,
            "activeAlertsCount" to activeAlerts.size,
            "updateInterval" to config.updateInterval.toString(),
            "lastUpdateTime" to (metricsHistory.values.maxByOrNull { it.timestamp }?.timestamp ?: 0)
        )
    }
    
    /**
     * 记录错误
     */
    fun recordError() {
        errorCount.incrementAndGet()
    }
    
    /**
     * 记录请求
     */
    fun recordRequest() {
        requestCount.incrementAndGet()
    }
    
    /**
     * 记录延迟
     */
    fun recordLatency(latencyMicros: Double) {
        synchronized(latencyHistory) {
            latencyHistory.add(latencyMicros)
            if (latencyHistory.size > 1000) {
                latencyHistory.removeAt(0)
            }
        }
    }
    
    /**
     * 记录吞吐量
     */
    fun recordThroughput(throughput: Double) {
        synchronized(throughputHistory) {
            throughputHistory.add(throughput)
            if (throughputHistory.size > 100) {
                throughputHistory.removeAt(0)
            }
        }
    }
    
    /**
     * 收集并发布指标
     */
    private suspend fun collectAndPublishMetrics() {
        val metrics = collectCurrentMetrics()
        
        // 存储历史数据
        metricsHistory[metrics.timestamp] = metrics
        
        // 检查告警阈值
        checkAlertThresholds(metrics)
        
        // 通知监听器
        notifyListeners(MonitoringEvent.MetricsUpdated(metrics))
    }
    
    /**
     * 收集当前指标
     */
    private fun collectCurrentMetrics(): RealTimeMetrics {
        val runtime = Runtime.getRuntime()
        val currentTime = System.currentTimeMillis()
        
        // 系统指标
        val systemMetrics = SystemMetrics(
            cpuUsage = getProcessCpuLoad() * 100,
            memoryUsage = ((runtime.totalMemory() - runtime.freeMemory()).toDouble() / runtime.maxMemory()) * 100,
            diskUsage = 0.0, // 简化实现
            networkIn = 0L,  // 简化实现
            networkOut = 0L, // 简化实现
            activeConnections = 0, // 简化实现
            threadCount = Thread.activeCount()
        )
        
        // 性能指标
        val performanceMetrics = PerformanceMetrics(
            throughput = calculateCurrentThroughput(),
            averageLatency = calculateAverageLatency(),
            p95Latency = calculatePercentileLatency(95.0),
            p99Latency = calculatePercentileLatency(99.0),
            errorRate = calculateErrorRate(),
            queueDepth = 0, // 简化实现
            processingRate = calculateProcessingRate()
        )
        
        // 业务指标
        val businessMetrics = BusinessMetrics(
            totalOrders = requestCount.get(),
            executedTrades = requestCount.get() - errorCount.get(),
            tradingVolume = 0.0, // 简化实现
            activeUsers = 0, // 简化实现
            rejectedOrders = errorCount.get(),
            averageOrderValue = 0.0 // 简化实现
        )
        
        // 告警指标
        val alertMetrics = AlertMetrics(
            criticalAlerts = activeAlerts.values.count { it.level == AlertLevel.CRITICAL },
            warningAlerts = activeAlerts.values.count { it.level == AlertLevel.WARNING },
            infoAlerts = activeAlerts.values.count { it.level == AlertLevel.INFO },
            lastAlertTime = activeAlerts.values.maxByOrNull { it.timestamp }?.timestamp ?: 0,
            alertRate = 0.0 // 简化实现
        )
        
        return RealTimeMetrics(
            timestamp = currentTime,
            nodeId = nodeId,
            systemMetrics = systemMetrics,
            performanceMetrics = performanceMetrics,
            businessMetrics = businessMetrics,
            alertMetrics = alertMetrics
        )
    }
    
    /**
     * 检查告警阈值
     */
    private suspend fun checkAlertThresholds(metrics: RealTimeMetrics) {
        val thresholds = config.alertThresholds
        
        // CPU使用率检查
        if (metrics.systemMetrics.cpuUsage > thresholds.cpuUsageThreshold) {
            triggerAlert(
                AlertLevel.WARNING,
                "CPU使用率过高",
                "CPU使用率达到 ${String.format("%.1f", metrics.systemMetrics.cpuUsage)}%",
                "cpu_usage",
                metrics.systemMetrics.cpuUsage,
                thresholds.cpuUsageThreshold
            )
        }
        
        // 内存使用率检查
        if (metrics.systemMetrics.memoryUsage > thresholds.memoryUsageThreshold) {
            triggerAlert(
                AlertLevel.WARNING,
                "内存使用率过高",
                "内存使用率达到 ${String.format("%.1f", metrics.systemMetrics.memoryUsage)}%",
                "memory_usage",
                metrics.systemMetrics.memoryUsage,
                thresholds.memoryUsageThreshold
            )
        }
        
        // 延迟检查
        if (metrics.performanceMetrics.p99Latency > thresholds.latencyThreshold) {
            triggerAlert(
                AlertLevel.CRITICAL,
                "延迟过高",
                "P99延迟达到 ${String.format("%.1f", metrics.performanceMetrics.p99Latency)} 微秒",
                "p99_latency",
                metrics.performanceMetrics.p99Latency,
                thresholds.latencyThreshold
            )
        }
        
        // 错误率检查
        if (metrics.performanceMetrics.errorRate > thresholds.errorRateThreshold) {
            triggerAlert(
                AlertLevel.CRITICAL,
                "错误率过高",
                "错误率达到 ${String.format("%.1f", metrics.performanceMetrics.errorRate)}%",
                "error_rate",
                metrics.performanceMetrics.errorRate,
                thresholds.errorRateThreshold
            )
        }
    }
    
    /**
     * 触发告警
     */
    private suspend fun triggerAlert(
        level: AlertLevel,
        title: String,
        message: String,
        metric: String,
        value: Double,
        threshold: Double
    ) {
        val alertId = "alert-${alertIdGenerator.incrementAndGet()}"
        val alert = MonitoringAlert(
            id = alertId,
            level = level,
            title = title,
            message = message,
            timestamp = System.currentTimeMillis(),
            nodeId = nodeId,
            metric = metric,
            value = value,
            threshold = threshold
        )
        
        activeAlerts[alertId] = alert
        notifyListeners(MonitoringEvent.AlertTriggered(alert))
        notifyListeners(MonitoringEvent.ThresholdExceeded(metric, value, threshold))
    }
    
    /**
     * 通知监听器
     */
    private suspend fun notifyListeners(event: MonitoringEvent) {
        listeners.forEach { listener ->
            try {
                listener.onEvent(event)
            } catch (e: Exception) {
                println("监控事件通知异常: ${e.message}")
            }
        }
    }
    
    /**
     * 清理旧的指标数据
     */
    private fun cleanupOldMetrics() {
        val cutoffTime = System.currentTimeMillis() - config.historyRetention.inWholeMilliseconds
        val keysToRemove = metricsHistory.keys.filter { it < cutoffTime }
        keysToRemove.forEach { metricsHistory.remove(it) }
        
        if (keysToRemove.isNotEmpty()) {
            println("清理了 ${keysToRemove.size} 条历史监控数据")
        }
    }
    
    // 辅助计算方法
    private fun getProcessCpuLoad(): Double {
        // 简化实现，实际应该使用JMX获取CPU使用率
        return Math.random() * 0.5 + 0.1 // 模拟10-60%的CPU使用率
    }
    
    private fun calculateCurrentThroughput(): Double {
        return synchronized(throughputHistory) {
            if (throughputHistory.isEmpty()) 0.0 else throughputHistory.average()
        }
    }
    
    private fun calculateAverageLatency(): Double {
        return synchronized(latencyHistory) {
            if (latencyHistory.isEmpty()) 0.0 else latencyHistory.average()
        }
    }
    
    private fun calculatePercentileLatency(percentile: Double): Double {
        return synchronized(latencyHistory) {
            if (latencyHistory.isEmpty()) return 0.0
            val sorted = latencyHistory.sorted()
            val index = ((percentile / 100.0) * sorted.size).toInt().coerceAtMost(sorted.size - 1)
            sorted[index]
        }
    }
    
    private fun calculateErrorRate(): Double {
        val total = requestCount.get()
        val errors = errorCount.get()
        return if (total > 0) (errors.toDouble() / total) * 100 else 0.0
    }
    
    private fun calculateProcessingRate(): Double {
        // 简化实现
        return calculateCurrentThroughput()
    }
}
