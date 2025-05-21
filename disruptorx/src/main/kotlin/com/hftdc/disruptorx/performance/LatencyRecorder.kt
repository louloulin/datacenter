package com.hftdc.disruptorx.performance

import org.HdrHistogram.Histogram
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/**
 * 延迟记录器
 * 使用HdrHistogram记录操作延迟并提供分析功能
 */
class LatencyRecorder(
    private val lowestTrackableValueNanos: Long = 1, // 1纳秒
    private val highestTrackableValueNanos: Long = TimeUnit.SECONDS.toNanos(10), // 10秒
    private val numberOfSignificantValueDigits: Int = 3
) {
    // 内部直方图
    private val histogram = Histogram(
        lowestTrackableValueNanos,
        highestTrackableValueNanos,
        numberOfSignificantValueDigits
    )
    
    // 起始时间
    private var startTimeNanos: Long = 0
    
    /**
     * 开始记录操作
     * @return 当前纳秒时间戳
     */
    fun startOperation(): Long {
        startTimeNanos = System.nanoTime()
        return startTimeNanos
    }
    
    /**
     * 结束记录操作并计算延迟
     * @return 操作延迟（纳秒）
     */
    fun endOperation(): Long {
        val endTimeNanos = System.nanoTime()
        val latencyNanos = endTimeNanos - startTimeNanos
        histogram.recordValue(latencyNanos)
        return latencyNanos
    }
    
    /**
     * 记录自定义延迟值
     * @param latencyNanos 延迟值（纳秒）
     */
    fun recordLatency(latencyNanos: Long) {
        histogram.recordValue(latencyNanos)
    }
    
    /**
     * 使用函数记录操作延迟
     * @param operation 要测量的操作
     * @return 操作的返回值
     */
    fun <T> measureOperation(operation: () -> T): T {
        startOperation()
        try {
            return operation()
        } finally {
            endOperation()
        }
    }
    
    /**
     * 重置直方图
     */
    fun reset() {
        histogram.reset()
    }
    
    /**
     * 获取延迟百分位数（纳秒）
     * @param percentile 百分位数（0-100）
     * @return 该百分位数的延迟值（纳秒）
     */
    fun getLatencyAtPercentile(percentile: Double): Long {
        return histogram.getValueAtPercentile(percentile)
    }
    
    /**
     * 获取最小延迟（纳秒）
     * @return 最小延迟值
     */
    fun getMinLatency(): Long {
        return histogram.minValue
    }
    
    /**
     * 获取最大延迟（纳秒）
     * @return 最大延迟值
     */
    fun getMaxLatency(): Long {
        return histogram.maxValue
    }
    
    /**
     * 获取平均延迟（纳秒）
     * @return 平均延迟值
     */
    fun getMeanLatency(): Double {
        return histogram.mean
    }
    
    /**
     * 打印延迟统计摘要
     * @param out 输出流
     */
    fun printSummary(out: PrintStream = System.out) {
        out.println("延迟统计摘要 (纳秒):")
        out.println("样本数: ${histogram.totalCount}")
        out.println("最小延迟: ${getMinLatency()}")
        out.println("最大延迟: ${getMaxLatency()}")
        out.println("平均延迟: ${getMeanLatency().toLong()}")
        out.println("中位数延迟: ${getLatencyAtPercentile(50.0)}")
        out.println("90%延迟: ${getLatencyAtPercentile(90.0)}")
        out.println("99%延迟: ${getLatencyAtPercentile(99.0)}")
        out.println("99.9%延迟: ${getLatencyAtPercentile(99.9)}")
        out.println("99.99%延迟: ${getLatencyAtPercentile(99.99)}")
    }
    
    /**
     * 导出直方图到CSV文件
     * @param filePath CSV文件路径
     */
    fun exportToCSV(filePath: String) {
        histogram.outputPercentileDistribution(
            PrintStream(filePath),
            1.0 // 输出百分比刻度
        )
    }
} 