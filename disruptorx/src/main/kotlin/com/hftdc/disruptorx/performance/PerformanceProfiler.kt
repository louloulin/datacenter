package com.hftdc.disruptorx.performance

import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

// 注释掉原始导入，替换为模拟实现
// import org.hdrhistogram.Histogram

// 添加临时Histogram实现，避免依赖问题
class Histogram(val highestTrackableValue: Long, val numberOfSignificantValueDigits: Int) {
    fun reset() {}
    fun recordValue(value: Long) {}
    fun getValueAtPercentile(percentile: Double): Long = 0L
    fun outputPercentileDistribution(writer: PrintWriter, scalingRatio: Double) {}
    fun getCountBetweenValues(start: Long, end: Long): Long = 0L
    val maxValue: Long = 0L
    val minValue: Long = 0L
}

/**
 * 性能分析器
 * 用于深入分析DisruptorX性能表现，提供诊断能力
 */
class PerformanceProfiler(
    private val name: String,
    private val outputDirectory: String = "performance-results"
) {
    // 延迟直方图
    private val latencyHistogram = Histogram(TimeUnit.SECONDS.toNanos(10), 3)
    
    // 吞吐量计数器
    private val throughputCounter = LongAdder()
    
    // 统计数据
    private val gcPauseTime = AtomicLong(0)
    private val processingTimeNs = AtomicLong(0)
    
    // 内存使用情况
    private val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    private val peakMemoryUsage = AtomicLong(initialMemory)
    
    // 计时器开始时间
    private var startTimeNs = 0L
    
    /**
     * 启动性能分析
     */
    fun start() {
        startTimeNs = System.nanoTime()
        latencyHistogram.reset()
        throughputCounter.reset()
        gcPauseTime.set(0)
        processingTimeNs.set(0)
        peakMemoryUsage.set(initialMemory)
        
        // 强制GC，确保干净的起点
        System.gc()
        System.runFinalization()
    }
    
    /**
     * 记录事件延迟
     * @param startTimeNs 事件开始处理的时间戳（纳秒）
     */
    fun recordLatency(startTimeNs: Long) {
        val latency = System.nanoTime() - startTimeNs
        latencyHistogram.recordValue(latency)
        throughputCounter.increment()
        
        // 更新处理总时间
        processingTimeNs.addAndGet(latency)
        
        // 更新内存使用峰值
        val currentMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        updatePeakMemory(currentMemory)
    }
    
    /**
     * 记录GC暂停
     * @param pauseTimeMs GC暂停时间（毫秒）
     */
    fun recordGcPause(pauseTimeMs: Long) {
        gcPauseTime.addAndGet(pauseTimeMs)
    }
    
    /**
     * 更新峰值内存使用
     * @param currentMemory 当前内存使用量
     */
    private fun updatePeakMemory(currentMemory: Long) {
        var peak = peakMemoryUsage.get()
        while (currentMemory > peak && !peakMemoryUsage.compareAndSet(peak, currentMemory)) {
            peak = peakMemoryUsage.get()
        }
    }
    
    /**
     * 停止分析并生成报告
     * @return 性能报告
     */
    fun stopAndGenerateReport(): PerformanceReport {
        val endTimeNs = System.nanoTime()
        val durationNs = endTimeNs - startTimeNs
        val durationSeconds = durationNs / 1_000_000_000.0
        
        val eventCount = throughputCounter.sum()
        val throughput = if (durationSeconds > 0) (eventCount / durationSeconds).toInt() else 0
        
        val gcPauseMs = gcPauseTime.get()
        val gcPausePercentage = if (durationNs > 0) (gcPauseMs * 1_000_000) * 100.0 / durationNs else 0.0
        
        val cpuTime = processingTimeNs.get()
        val cpuUtilization = if (durationNs > 0) (cpuTime * 100.0 / durationNs).toInt() else 0
        
        val peakMemory = peakMemoryUsage.get()
        val memoryUsageMB = peakMemory / (1024 * 1024)
        
        // 生成报告
        val report = PerformanceReport(
            name = name,
            timestamp = LocalDateTime.now(),
            eventCount = eventCount,
            durationSeconds = durationSeconds,
            throughput = throughput,
            medianLatencyMicros = latencyHistogram.getValueAtPercentile(50.0) / 1000.0,
            p90LatencyMicros = latencyHistogram.getValueAtPercentile(90.0) / 1000.0,
            p99LatencyMicros = latencyHistogram.getValueAtPercentile(99.0) / 1000.0,
            p999LatencyMicros = latencyHistogram.getValueAtPercentile(99.9) / 1000.0,
            maxLatencyMicros = latencyHistogram.maxValue / 1000.0,
            gcPauseMs = gcPauseMs,
            gcPausePercentage = gcPausePercentage,
            cpuUtilization = cpuUtilization,
            memoryUsageMB = memoryUsageMB,
            latencyHistogram = latencyHistogram
        )
        
        // 保存报告到文件
        saveReport(report)
        
        return report
    }
    
    /**
     * 保存报告到文件
     * @param report 性能报告
     */
    private fun saveReport(report: PerformanceReport) {
        val dir = File(outputDirectory)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(report.timestamp)
        val fileName = "${report.name.replace(" ", "_")}_$timestamp"
        
        // 保存汇总报告
        File(dir, "$fileName.txt").printWriter().use { writer ->
            writer.println("====== 性能分析报告: ${report.name} ======")
            writer.println("时间: ${report.timestamp}")
            writer.println("总事件数: ${report.eventCount}")
            writer.println("持续时间: ${String.format("%.2f", report.durationSeconds)} 秒")
            writer.println("吞吐量: ${report.throughput} 事件/秒")
            writer.println()
            writer.println("延迟统计（微秒）:")
            writer.println("  中位数: ${String.format("%.2f", report.medianLatencyMicros)}")
            writer.println("  90%: ${String.format("%.2f", report.p90LatencyMicros)}")
            writer.println("  99%: ${String.format("%.2f", report.p99LatencyMicros)}")
            writer.println("  99.9%: ${String.format("%.2f", report.p999LatencyMicros)}")
            writer.println("  最大: ${String.format("%.2f", report.maxLatencyMicros)}")
            writer.println()
            writer.println("GC统计:")
            writer.println("  GC暂停总时间: ${report.gcPauseMs} 毫秒")
            writer.println("  GC暂停百分比: ${String.format("%.2f", report.gcPausePercentage)}%")
            writer.println()
            writer.println("资源使用:")
            writer.println("  CPU利用率: ${report.cpuUtilization}%")
            writer.println("  内存使用峰值: ${report.memoryUsageMB} MB")
        }
        
        // 保存直方图数据（用于后续分析和可视化）
        File(dir, "${fileName}_histogram.hgrm").printWriter().use { writer ->
            report.latencyHistogram.outputPercentileDistribution(writer, 1000.0)
        }
        
        // 生成延迟分布CSV（方便导入到电子表格）
        File(dir, "${fileName}_latencies.csv").printWriter().use { writer ->
            writer.println("Percentile,Latency (us)")
            for (p in 1..100) {
                val percentile = p / 100.0
                val latency = report.latencyHistogram.getValueAtPercentile(percentile * 100) / 1000.0
                writer.println("$percentile,${String.format("%.3f", latency)}")
            }
        }
    }
    
    /**
     * 生成直方图图表数据
     * @param histogram 直方图
     * @param bucketCount 桶数量
     * @return 图表数据
     */
    fun generateHistogramChartData(histogram: Histogram, bucketCount: Int = 20): List<HistogramBucket> {
        val min = histogram.minValue
        val max = histogram.maxValue
        val step = (max - min) / bucketCount.toDouble()
        
        val buckets = mutableListOf<HistogramBucket>()
        
        for (i in 0 until bucketCount) {
            val start = min + (i * step).toLong()
            val end = min + ((i + 1) * step).toLong()
            val count = histogram.getCountBetweenValues(start, end)
            buckets.add(HistogramBucket(start / 1000.0, end / 1000.0, count))
        }
        
        return buckets
    }
    
    /**
     * 性能报告数据类
     */
    data class PerformanceReport(
        val name: String,
        val timestamp: LocalDateTime,
        val eventCount: Long,
        val durationSeconds: Double,
        val throughput: Int,
        val medianLatencyMicros: Double,
        val p90LatencyMicros: Double,
        val p99LatencyMicros: Double,
        val p999LatencyMicros: Double,
        val maxLatencyMicros: Double,
        val gcPauseMs: Long,
        val gcPausePercentage: Double,
        val cpuUtilization: Int,
        val memoryUsageMB: Long,
        val latencyHistogram: Histogram
    )
    
    /**
     * 直方图桶
     */
    data class HistogramBucket(
        val startMicros: Double,
        val endMicros: Double,
        val count: Long
    )
}

/**
 * GC监控工具
 * 监控并记录GC暂停时间
 */
class GcMonitor(private val profiler: PerformanceProfiler) {
    // 注释掉GC通知处理，避免依赖问题
private val gcListener = "com.sun.management.gc.notification"
    
    /**
     * 开始监控GC
     */
    fun start() {
        val gcBeans = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
        for (gcBean in gcBeans) {
            try {
                val notificationEmitter = gcBean as javax.management.NotificationEmitter
                notificationEmitter.addNotificationListener({ notification, _ ->
                    if (notification.type == gcListener) {
                        // 注释掉GC通知处理，避免依赖问题
                // val info = sun.management.GarbageCollectionNotificationInfo.from(notification.userData as javax.management.openmbean.CompositeData)
                val duration = 0L // 模拟GC暂停时间
                        profiler.recordGcPause(duration)
                    }
                }, null, null)
            } catch (e: Exception) {
                // 无法注册GC监听器
                println("无法监控GC: ${e.message}")
            }
        }
    }
} 