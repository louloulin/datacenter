package com.hftdc.disruptorx.monitoring

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 指标收集器
 * 负责收集和聚合系统性能指标
 */
class MetricsCollector {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val gauges = ConcurrentHashMap<String, DoubleAdder>()
    private val histograms = ConcurrentHashMap<String, Histogram>()
    private val timers = ConcurrentHashMap<String, Timer>()
    
    private val metricsChannel = Channel<MetricEvent>(Channel.UNLIMITED)
    private val mutex = Mutex()
    
    // 指标标签
    private val metricTags = ConcurrentHashMap<String, Map<String, String>>()
    
    // 指标导出器
    private val exporters = mutableListOf<MetricsExporter>()
    
    init {
        // 启动指标处理协程
        CoroutineScope(Dispatchers.Default).launch {
            processMetrics()
        }
        
        // 启动定期导出协程
        CoroutineScope(Dispatchers.Default).launch {
            exportMetricsPeriodically()
        }
    }
    
    /**
     * 增加计数器
     */
    fun incrementCounter(name: String, delta: Long = 1, tags: Map<String, String> = emptyMap()) {
        val metricName = buildMetricName(name, tags)
        counters.computeIfAbsent(metricName) { AtomicLong(0) }.addAndGet(delta)
        
        if (tags.isNotEmpty()) {
            metricTags[metricName] = tags
        }
        
        // 发送指标事件
        metricsChannel.trySend(MetricEvent.CounterEvent(metricName, delta, System.currentTimeMillis()))
    }
    
    /**
     * 设置仪表盘值
     */
    fun setGauge(name: String, value: Double, tags: Map<String, String> = emptyMap()) {
        val metricName = buildMetricName(name, tags)
        val gauge = gauges.computeIfAbsent(metricName) { DoubleAdder() }
        
        // 重置并设置新值
        gauge.reset()
        gauge.add(value)
        
        if (tags.isNotEmpty()) {
            metricTags[metricName] = tags
        }
        
        metricsChannel.trySend(MetricEvent.GaugeEvent(metricName, value, System.currentTimeMillis()))
    }
    
    /**
     * 记录直方图值
     */
    suspend fun recordHistogram(name: String, value: Double, tags: Map<String, String> = emptyMap()) {
        val metricName = buildMetricName(name, tags)
        val histogram = histograms.computeIfAbsent(metricName) { Histogram() }
        histogram.record(value)
        
        if (tags.isNotEmpty()) {
            metricTags[metricName] = tags
        }
        
        metricsChannel.trySend(MetricEvent.HistogramEvent(metricName, value, System.currentTimeMillis()))
    }
    
    /**
     * 开始计时
     */
    fun startTimer(name: String, tags: Map<String, String> = emptyMap()): TimerContext {
        val metricName = buildMetricName(name, tags)
        val timer = timers.computeIfAbsent(metricName) { Timer() }
        
        if (tags.isNotEmpty()) {
            metricTags[metricName] = tags
        }
        
        return TimerContext(metricName, System.nanoTime(), this)
    }
    
    /**
     * 记录计时器值
     */
    internal suspend fun recordTimer(name: String, durationNanos: Long) {
        val timer = timers[name] ?: return
        timer.record(durationNanos)
        
        val durationMillis = durationNanos / 1_000_000.0
        metricsChannel.trySend(MetricEvent.TimerEvent(name, durationMillis, System.currentTimeMillis()))
    }
    
    /**
     * 获取计数器值
     */
    fun getCounter(name: String, tags: Map<String, String> = emptyMap()): Long {
        val metricName = buildMetricName(name, tags)
        return counters[metricName]?.get() ?: 0L
    }
    
    /**
     * 获取仪表盘值
     */
    fun getGauge(name: String, tags: Map<String, String> = emptyMap()): Double {
        val metricName = buildMetricName(name, tags)
        return gauges[metricName]?.sum() ?: 0.0
    }
    
    /**
     * 获取直方图统计
     */
    suspend fun getHistogramStats(name: String, tags: Map<String, String> = emptyMap()): HistogramStats? {
        val metricName = buildMetricName(name, tags)
        return histograms[metricName]?.getStats()
    }
    
    /**
     * 获取计时器统计
     */
    suspend fun getTimerStats(name: String, tags: Map<String, String> = emptyMap()): TimerStats? {
        val metricName = buildMetricName(name, tags)
        return timers[metricName]?.getStats()
    }
    
    /**
     * 获取所有指标
     */
    suspend fun getAllMetrics(): MetricsSnapshot {
        mutex.withLock {
            val counterSnapshots = counters.mapValues { it.value.get() }
            val gaugeSnapshots = gauges.mapValues { it.value.sum() }
            val histogramSnapshots = histograms.mapValues { it.value.getStats() }
            val timerSnapshots = timers.mapValues { it.value.getStats() }
            
            return MetricsSnapshot(
                timestamp = System.currentTimeMillis(),
                counters = counterSnapshots,
                gauges = gaugeSnapshots,
                histograms = histogramSnapshots,
                timers = timerSnapshots,
                tags = metricTags.toMap()
            )
        }
    }
    
    /**
     * 添加指标导出器
     */
    fun addExporter(exporter: MetricsExporter) {
        exporters.add(exporter)
    }
    
    /**
     * 移除指标导出器
     */
    fun removeExporter(exporter: MetricsExporter) {
        exporters.remove(exporter)
    }
    
    /**
     * 重置所有指标
     */
    suspend fun reset() {
        mutex.withLock {
            counters.clear()
            gauges.clear()
            histograms.clear()
            timers.clear()
            metricTags.clear()
        }
    }
    
    /**
     * 构建指标名称
     */
    private fun buildMetricName(name: String, tags: Map<String, String>): String {
        if (tags.isEmpty()) return name
        
        val tagString = tags.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
        
        return "$name{$tagString}"
    }
    
    /**
     * 处理指标事件
     */
    private suspend fun processMetrics() {
        for (event in metricsChannel) {
            // 这里可以添加实时指标处理逻辑
            // 例如：异常检测、告警触发等
            when (event) {
                is MetricEvent.CounterEvent -> {
                    // 处理计数器事件
                }
                is MetricEvent.GaugeEvent -> {
                    // 处理仪表盘事件
                }
                is MetricEvent.HistogramEvent -> {
                    // 处理直方图事件
                }
                is MetricEvent.TimerEvent -> {
                    // 处理计时器事件
                }
            }
        }
    }
    
    /**
     * 定期导出指标
     */
    private suspend fun exportMetricsPeriodically() {
        while (true) {
            delay(EXPORT_INTERVAL)
            
            try {
                val snapshot = getAllMetrics()
                exporters.forEach { exporter ->
                    try {
                        exporter.export(snapshot)
                    } catch (e: Exception) {
                        // 记录导出错误，但不影响其他导出器
                    }
                }
            } catch (e: Exception) {
                // 记录错误
            }
        }
    }
    
    companion object {
        private val EXPORT_INTERVAL = 10.seconds
    }
}

/**
 * 计时器上下文
 */
class TimerContext(
    private val name: String,
    private val startTime: Long,
    private val collector: MetricsCollector
) {
    suspend fun stop() {
        val duration = System.nanoTime() - startTime
        collector.recordTimer(name, duration)
    }
}

/**
 * 直方图
 */
class Histogram {
    private val values = mutableListOf<Double>()
    private val mutex = Mutex()
    
    suspend fun record(value: Double) {
        mutex.withLock {
            values.add(value)
            // 保持最近的1000个值
            if (values.size > 1000) {
                values.removeAt(0)
            }
        }
    }
    
    suspend fun getStats(): HistogramStats {
        mutex.withLock {
            if (values.isEmpty()) {
                return HistogramStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            }
            
            val sorted = values.sorted()
            val count = values.size
            val sum = values.sum()
            val mean = sum / count
            val min = sorted.first()
            val max = sorted.last()
            
            val p50 = percentile(sorted, 0.5)
            val p95 = percentile(sorted, 0.95)
            val p99 = percentile(sorted, 0.99)
            
            return HistogramStats(count, sum, mean, min, max, p50, p95, p99)
        }
    }
    
    private fun percentile(sorted: List<Double>, percentile: Double): Double {
        val index = (percentile * (sorted.size - 1)).toInt()
        return sorted[index]
    }
}

/**
 * 计时器
 */
class Timer {
    private val durations = mutableListOf<Long>()
    private val mutex = Mutex()
    
    suspend fun record(durationNanos: Long) {
        mutex.withLock {
            durations.add(durationNanos)
            // 保持最近的1000个值
            if (durations.size > 1000) {
                durations.removeAt(0)
            }
        }
    }
    
    suspend fun getStats(): TimerStats {
        mutex.withLock {
            if (durations.isEmpty()) {
                return TimerStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            }
            
            val sorted = durations.sorted()
            val count = durations.size
            val sum = durations.sum().toDouble() / 1_000_000.0 // 转换为毫秒
            val mean = sum / count
            val min = sorted.first().toDouble() / 1_000_000.0
            val max = sorted.last().toDouble() / 1_000_000.0
            
            val p50 = sorted[(0.5 * (sorted.size - 1)).toInt()].toDouble() / 1_000_000.0
            val p95 = sorted[(0.95 * (sorted.size - 1)).toInt()].toDouble() / 1_000_000.0
            val p99 = sorted[(0.99 * (sorted.size - 1)).toInt()].toDouble() / 1_000_000.0
            
            return TimerStats(count, sum, mean, min, max, p50, p95, p99)
        }
    }
}

/**
 * 直方图统计
 */
data class HistogramStats(
    val count: Int,
    val sum: Double,
    val mean: Double,
    val min: Double,
    val max: Double,
    val p50: Double,
    val p95: Double,
    val p99: Double
)

/**
 * 计时器统计
 */
data class TimerStats(
    val count: Int,
    val sum: Double, // 毫秒
    val mean: Double, // 毫秒
    val min: Double, // 毫秒
    val max: Double, // 毫秒
    val p50: Double, // 毫秒
    val p95: Double, // 毫秒
    val p99: Double // 毫秒
)

/**
 * 指标快照
 */
data class MetricsSnapshot(
    val timestamp: Long,
    val counters: Map<String, Long>,
    val gauges: Map<String, Double>,
    val histograms: Map<String, HistogramStats>,
    val timers: Map<String, TimerStats>,
    val tags: Map<String, Map<String, String>>
)

/**
 * 指标事件
 */
sealed class MetricEvent {
    data class CounterEvent(
        val name: String,
        val delta: Long,
        val timestamp: Long
    ) : MetricEvent()
    
    data class GaugeEvent(
        val name: String,
        val value: Double,
        val timestamp: Long
    ) : MetricEvent()
    
    data class HistogramEvent(
        val name: String,
        val value: Double,
        val timestamp: Long
    ) : MetricEvent()
    
    data class TimerEvent(
        val name: String,
        val duration: Double, // 毫秒
        val timestamp: Long
    ) : MetricEvent()
}

/**
 * 指标导出器接口
 */
interface MetricsExporter {
    suspend fun export(snapshot: MetricsSnapshot)
}

/**
 * 控制台指标导出器
 */
class ConsoleMetricsExporter : MetricsExporter {
    override suspend fun export(snapshot: MetricsSnapshot) {
        println("=== Metrics Snapshot at ${snapshot.timestamp} ===")
        
        if (snapshot.counters.isNotEmpty()) {
            println("Counters:")
            snapshot.counters.forEach { (name, value) ->
                println("  $name: $value")
            }
        }
        
        if (snapshot.gauges.isNotEmpty()) {
            println("Gauges:")
            snapshot.gauges.forEach { (name, value) ->
                println("  $name: $value")
            }
        }
        
        if (snapshot.histograms.isNotEmpty()) {
            println("Histograms:")
            snapshot.histograms.forEach { (name, stats) ->
                println("  $name: count=${stats.count}, mean=${String.format("%.2f", stats.mean)}, p95=${String.format("%.2f", stats.p95)}")
            }
        }
        
        if (snapshot.timers.isNotEmpty()) {
            println("Timers:")
            snapshot.timers.forEach { (name, stats) ->
                println("  $name: count=${stats.count}, mean=${String.format("%.2f", stats.mean)}ms, p95=${String.format("%.2f", stats.p95)}ms")
            }
        }
        
        println()
    }
}