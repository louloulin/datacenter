package com.hftdc.disruptorx.benchmark

import com.hftdc.disruptorx.BenchmarkMessage
import org.HdrHistogram.Histogram
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * 延迟记录器
 * 用于记录消息处理延迟，支持统计分析
 */
class LatencyRecorder {
    private val histogram = Histogram(TimeUnit.SECONDS.toNanos(10), 3)
    
    /**
     * 记录延迟时间
     */
    fun recordLatency(nanos: Long) {
        histogram.recordValue(nanos)
    }
    
    /**
     * 获取延迟直方图的副本
     */
    fun getHistogram(): Histogram {
        return histogram.copy()
    }
    
    /**
     * 重置记录器
     */
    fun reset() {
        histogram.reset()
    }
}

/**
 * 消息处理器
 * 模拟业务处理逻辑并记录延迟
 */
class MessageHandler(private val latencyRecorder: LatencyRecorder) {
    /**
     * 处理基准测试消息
     */
    fun handleMessage(message: BenchmarkMessage) {
        // 计算延迟
        val latencyNanos = System.nanoTime() - extractTimestamp(message)
        
        // 记录延迟
        latencyRecorder.recordLatency(latencyNanos)
        
        // 模拟一些处理逻辑（可选）
        simulateProcessing(message)
    }
    
    /**
     * 从消息中提取时间戳
     * 注意：在实际基准测试中，时间戳应该作为消息的一部分发送
     * 这里简化为假设时间戳在负载的前8个字节
     */
    private fun extractTimestamp(message: BenchmarkMessage): Long {
        if (message.payload.size >= 8) {
            return ByteBuffer.wrap(message.payload, 0, 8).getLong()
        }
        return 0L
    }
    
    /**
     * 模拟消息处理逻辑
     */
    private fun simulateProcessing(message: BenchmarkMessage) {
        // 可以添加一些CPU计算来模拟实际处理
        // 例如简单的CRC计算或其他操作
        var checksum = 0L
        for (byte in message.payload) {
            checksum = (checksum * 31) + byte
        }
        
        // 防止JIT优化删除这个计算
        if (checksum == Long.MIN_VALUE) {
            println("Unlikely checksum: $checksum")
        }
    }
} 