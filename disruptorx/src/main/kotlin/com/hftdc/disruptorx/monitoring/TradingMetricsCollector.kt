package com.hftdc.disruptorx.monitoring

import kotlinx.coroutines.*
import org.HdrHistogram.Histogram
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 延迟统计信息
 */
data class LatencyStats(
    val p50: Duration,
    val p95: Duration,
    val p99: Duration,
    val p999: Duration,
    val max: Duration,
    val mean: Duration,
    val count: Long
) {
    override fun toString(): String {
        return "LatencyStats(p50=${p50.inWholeMicroseconds}μs, p95=${p95.inWholeMicroseconds}μs, " +
                "p99=${p99.inWholeMicroseconds}μs, p99.9=${p999.inWholeMicroseconds}μs, " +
                "max=${max.inWholeMicroseconds}μs, count=$count)"
    }
}

/**
 * 吞吐量统计信息
 */
data class ThroughputStats(
    val totalCount: Long,
    val ratePerSecond: Double,
    val ratePerMinute: Double,
    val peakRate: Double,
    val windowStart: Long,
    val windowEnd: Long
) {
    override fun toString(): String {
        return "ThroughputStats(total=$totalCount, rate=${String.format("%.2f", ratePerSecond)}/s, " +
                "peak=${String.format("%.2f", peakRate)}/s)"
    }
}

/**
 * 告警类型
 */
enum class AlertType {
    LATENCY_BREACH,     // 延迟超标
    THROUGHPUT_LOW,     // 吞吐量过低
    ERROR_RATE_HIGH,    // 错误率过高
    MEMORY_PRESSURE,    // 内存压力
    CPU_HIGH,           // CPU使用率过高
    DISK_FULL          // 磁盘空间不足
}

/**
 * 告警信息
 */
data class Alert(
    val type: AlertType,
    val message: String,
    val severity: AlertSeverity,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * 告警严重程度
 */
enum class AlertSeverity {
    INFO, WARNING, CRITICAL
}

/**
 * 交易系统指标收集器
 * 专为高频交易系统设计的高性能指标收集器，支持：
 * 1. 微秒级延迟统计
 * 2. 高精度吞吐量测量
 * 3. 实时告警
 * 4. 业务指标追踪
 */
class TradingMetricsCollector(
    private val slaThresholdNanos: Long = 50_000, // 50μs SLA
    private val alertManager: AlertManager = DefaultAlertManager(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    
    // 延迟直方图 - 使用HdrHistogram获得高精度统计
    private val latencyHistograms = ConcurrentHashMap<String, Histogram>()
    
    // 吞吐量计数器
    private val throughputCounters = ConcurrentHashMap<String, LongAdder>()
    
    // 错误计数器
    private val errorCounters = ConcurrentHashMap<String, LongAdder>()
    
    // 业务指标
    private val tradeVolumes = ConcurrentHashMap<String, AtomicLong>()
    private val pnlTrackers = ConcurrentHashMap<String, AtomicLong>()
    
    // 时间窗口统计
    private val windowStats = ConcurrentHashMap<String, WindowStats>()
    
    // 峰值追踪
    private val peakRates = ConcurrentHashMap<String, AtomicLong>()
    
    // 最后更新时间
    private val lastUpdateTimes = ConcurrentHashMap<String, AtomicLong>()
    
    init {
        // 启动定期统计任务
        startPeriodicStatsCollection()
    }
    
    /**
     * 记录交易延迟
     */
    fun recordTradeLatency(operation: String, latencyNanos: Long) {
        val histogram = latencyHistograms.computeIfAbsent(operation) {
            Histogram(1, 10_000_000, 3) // 1ns到10ms，3位精度
        }
        
        histogram.recordValue(latencyNanos)
        
        // 实时SLA检查
        if (latencyNanos > slaThresholdNanos) {
            val alert = Alert(
                type = AlertType.LATENCY_BREACH,
                message = "Trade latency exceeded SLA: ${latencyNanos / 1000}μs for operation $operation",
                severity = if (latencyNanos > slaThresholdNanos * 2) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
                metadata = mapOf(
                    "operation" to operation,
                    "latencyMicros" to latencyNanos / 1000,
                    "slaThresholdMicros" to slaThresholdNanos / 1000
                )
            )
            alertManager.sendAlert(alert)
        }
        
        updateLastAccessTime(operation)
    }
    
    /**
     * 记录延迟（Duration版本）
     */
    fun recordLatency(operation: String, latency: Duration) {
        recordTradeLatency(operation, latency.inWholeNanoseconds)
    }
    
    /**
     * 记录吞吐量
     */
    fun recordThroughput(operation: String, count: Long = 1) {
        val counter = throughputCounters.computeIfAbsent(operation) { LongAdder() }
        counter.add(count)
        
        // 更新窗口统计
        updateWindowStats(operation, count)
        updateLastAccessTime(operation)
    }
    
    /**
     * 记录错误
     */
    fun recordError(operation: String, count: Long = 1) {
        val counter = errorCounters.computeIfAbsent(operation) { LongAdder() }
        counter.add(count)
        updateLastAccessTime(operation)
    }
    
    /**
     * 记录交易量
     */
    fun recordTradeVolume(symbol: String, volume: BigDecimal) {
        val volumeCounter = tradeVolumes.computeIfAbsent(symbol) { AtomicLong(0) }
        volumeCounter.addAndGet(volume.multiply(BigDecimal(1000)).toLong()) // 转换为整数存储
        updateLastAccessTime("volume_$symbol")
    }
    
    /**
     * 记录PnL
     */
    fun recordPnL(strategy: String, pnl: BigDecimal) {
        val pnlTracker = pnlTrackers.computeIfAbsent(strategy) { AtomicLong(0) }
        pnlTracker.addAndGet(pnl.multiply(BigDecimal(1000)).toLong()) // 转换为整数存储
        updateLastAccessTime("pnl_$strategy")
    }
    
    /**
     * 获取延迟统计
     */
    suspend fun getLatencyStats(operation: String): LatencyStats? {
        val histogram = latencyHistograms[operation] ?: return null
        
        return LatencyStats(
            p50 = histogram.getValueAtPercentile(50.0).nanoseconds,
            p95 = histogram.getValueAtPercentile(95.0).nanoseconds,
            p99 = histogram.getValueAtPercentile(99.0).nanoseconds,
            p999 = histogram.getValueAtPercentile(99.9).nanoseconds,
            max = histogram.maxValue.nanoseconds,
            mean = histogram.mean.toLong().nanoseconds,
            count = histogram.totalCount
        )
    }
    
    /**
     * 获取吞吐量统计
     */
    suspend fun getThroughputStats(operation: String): ThroughputStats? {
        val counter = throughputCounters[operation] ?: return null
        val windowStat = windowStats[operation] ?: return null
        val peakRate = peakRates[operation]?.get()?.toDouble() ?: 0.0
        
        val now = System.currentTimeMillis()
        val windowDurationSeconds = (now - windowStat.windowStart) / 1000.0
        val ratePerSecond = if (windowDurationSeconds > 0) {
            windowStat.currentCount.toDouble() / windowDurationSeconds
        } else 0.0
        
        return ThroughputStats(
            totalCount = counter.sum(),
            ratePerSecond = ratePerSecond,
            ratePerMinute = ratePerSecond * 60,
            peakRate = peakRate,
            windowStart = windowStat.windowStart,
            windowEnd = now
        )
    }
    
    /**
     * 获取所有操作的延迟统计
     */
    suspend fun getAllLatencyStats(): Map<String, LatencyStats> {
        return latencyHistograms.keys.mapNotNull { operation ->
            getLatencyStats(operation)?.let { operation to it }
        }.toMap()
    }
    
    /**
     * 获取所有操作的吞吐量统计
     */
    suspend fun getAllThroughputStats(): Map<String, ThroughputStats> {
        return throughputCounters.keys.mapNotNull { operation ->
            getThroughputStats(operation)?.let { operation to it }
        }.toMap()
    }
    
    /**
     * 获取交易量统计
     */
    fun getTradeVolumeStats(): Map<String, BigDecimal> {
        return tradeVolumes.mapValues { (_, volume) ->
            BigDecimal(volume.get()).divide(BigDecimal(1000))
        }
    }
    
    /**
     * 获取PnL统计
     */
    fun getPnLStats(): Map<String, BigDecimal> {
        return pnlTrackers.mapValues { (_, pnl) ->
            BigDecimal(pnl.get()).divide(BigDecimal(1000))
        }
    }
    
    /**
     * 重置指标
     */
    fun resetMetrics(operation: String) {
        latencyHistograms[operation]?.reset()
        throughputCounters[operation]?.reset()
        errorCounters[operation]?.reset()
        windowStats[operation]?.reset()
        peakRates[operation]?.set(0)
    }
    
    /**
     * 重置所有指标
     */
    fun resetAllMetrics() {
        latencyHistograms.values.forEach { it.reset() }
        throughputCounters.values.forEach { it.reset() }
        errorCounters.values.forEach { it.reset() }
        windowStats.values.forEach { it.reset() }
        peakRates.values.forEach { it.set(0) }
        tradeVolumes.values.forEach { it.set(0) }
        pnlTrackers.values.forEach { it.set(0) }
    }
    
    /**
     * 更新窗口统计
     */
    private fun updateWindowStats(operation: String, count: Long) {
        val windowStat = windowStats.computeIfAbsent(operation) { WindowStats() }
        windowStat.addCount(count)
        
        // 更新峰值速率
        val currentRate = windowStat.getCurrentRate()
        val peakRate = peakRates.computeIfAbsent(operation) { AtomicLong(0) }
        
        var current = peakRate.get()
        while (currentRate > current && !peakRate.compareAndSet(current, currentRate.toLong())) {
            current = peakRate.get()
        }
    }
    
    /**
     * 更新最后访问时间
     */
    private fun updateLastAccessTime(operation: String) {
        lastUpdateTimes.computeIfAbsent(operation) { AtomicLong(0) }
            .set(System.currentTimeMillis())
    }
    
    /**
     * 启动定期统计收集
     */
    private fun startPeriodicStatsCollection() {
        scope.launch {
            while (isActive) {
                try {
                    collectPeriodicStats()
                    delay(1.seconds)
                } catch (e: Exception) {
                    println("Error in periodic stats collection: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 定期统计收集
     */
    private suspend fun collectPeriodicStats() {
        val now = System.currentTimeMillis()
        
        // 清理过期的指标
        val expireTime = now - 300_000 // 5分钟过期
        
        lastUpdateTimes.entries.removeIf { (operation, lastTime) ->
            if (lastTime.get() < expireTime) {
                latencyHistograms.remove(operation)
                throughputCounters.remove(operation)
                errorCounters.remove(operation)
                windowStats.remove(operation)
                peakRates.remove(operation)
                true
            } else {
                false
            }
        }
        
        // 重置窗口统计
        windowStats.values.forEach { it.rotateWindow() }
    }
}

/**
 * 窗口统计
 */
class WindowStats {
    @Volatile
    var windowStart = System.currentTimeMillis()
    @Volatile
    var currentCount = 0L
    private val windowDuration = 60_000L // 1分钟窗口
    
    @Synchronized
    fun addCount(count: Long) {
        currentCount += count
    }
    
    @Synchronized
    fun getCurrentRate(): Double {
        val now = System.currentTimeMillis()
        val duration = (now - windowStart) / 1000.0
        return if (duration > 0) currentCount.toDouble() / duration else 0.0
    }
    
    @Synchronized
    fun rotateWindow() {
        val now = System.currentTimeMillis()
        if (now - windowStart > windowDuration) {
            windowStart = now
            currentCount = 0
        }
    }
    
    @Synchronized
    fun reset() {
        windowStart = System.currentTimeMillis()
        currentCount = 0
    }
}

/**
 * 告警管理器接口
 */
interface AlertManager {
    fun sendAlert(alert: Alert)
}

/**
 * 默认告警管理器
 */
class DefaultAlertManager : AlertManager {
    override fun sendAlert(alert: Alert) {
        println("ALERT [${alert.severity}] ${alert.type}: ${alert.message}")
    }
}
