package com.hftdc.disruptorx.benchmark

import org.hdrhistogram.Histogram

/**
 * 基准测试结果
 * 保存基准测试的执行结果和性能指标
 */
data class BenchmarkResult(
    /**
     * 处理的消息总数
     */
    val totalMessages: Long,
    
    /**
     * 测试持续时间（纳秒）
     */
    val duration: Long,
    
    /**
     * 吞吐量（消息/秒）
     */
    val throughput: Double,
    
    /**
     * 消息处理延迟直方图
     */
    val latencyHistogram: Histogram,
    
    /**
     * 消息发布率直方图
     */
    val messageRateHistogram: Histogram,
    
    /**
     * 错误计数
     */
    val errorCount: Long
) {
    /**
     * 格式化结果输出
     */
    override fun toString(): String {
        return buildString {
            append("基准测试结果:\n")
            append("总消息数: $totalMessages\n")
            append("持续时间: ${duration / 1_000_000_000.0} 秒\n")
            append("吞吐量: ${String.format("%.2f", throughput)} 消息/秒\n")
            append("延迟(微秒):\n")
            append("  中位数: ${latencyHistogram.getValueAtPercentile(50.0) / 1000.0}\n")
            append("  90%: ${latencyHistogram.getValueAtPercentile(90.0) / 1000.0}\n")
            append("  99%: ${latencyHistogram.getValueAtPercentile(99.0) / 1000.0}\n")
            append("  99.9%: ${latencyHistogram.getValueAtPercentile(99.9) / 1000.0}\n")
            append("  最大: ${latencyHistogram.maxValue / 1000.0}\n")
            append("发布率(微秒/消息):\n")
            append("  中位数: ${messageRateHistogram.getValueAtPercentile(50.0) / 1000.0}\n")
            append("  90%: ${messageRateHistogram.getValueAtPercentile(90.0) / 1000.0}\n")
            append("  99%: ${messageRateHistogram.getValueAtPercentile(99.0) / 1000.0}\n")
            append("错误数: $errorCount")
        }
    }
} 