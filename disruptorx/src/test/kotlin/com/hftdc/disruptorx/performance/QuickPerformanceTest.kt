package com.hftdc.disruptorx.performance

import com.hftdc.disruptorx.monitoring.TradingMetricsCollector
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

/**
 * 快速性能测试
 */
@ExperimentalTime
class QuickPerformanceTest {

    @Test
    fun `快速性能验证`() = runBlocking {
        println("=== DisruptorX 快速性能验证 ===")
        
        // 1. 系统信息
        val runtime = Runtime.getRuntime()
        println("\n🖥️ 系统信息:")
        println("  可用处理器: ${runtime.availableProcessors()}")
        println("  最大内存: ${runtime.maxMemory() / 1024 / 1024} MB")
        println("  总内存: ${runtime.totalMemory() / 1024 / 1024} MB")
        println("  可用内存: ${runtime.freeMemory() / 1024 / 1024} MB")
        
        // 2. 内存管理性能
        val memoryManager = EnhancedMemoryManager()
        val iterations = 10000
        
        val memoryTime = measureTime {
            repeat(iterations) {
                memoryManager.useByteBuffer(1024) { buffer ->
                    buffer.putInt(42)
                    buffer.flip()
                    buffer.getInt()
                }
            }
        }
        
        val memoryStats = memoryManager.getMemoryStats()
        println("\n💾 内存管理性能:")
        println("  执行时间: $memoryTime")
        println("  池命中率: ${String.format("%.2f", memoryStats.poolHitRate * 100)}%")
        println("  操作速度: ${String.format("%.2f", iterations.toDouble() / memoryTime.inWholeMilliseconds * 1000)} ops/sec")
        
        // 3. 指标收集性能
        val metricsCollector = TradingMetricsCollector()
        val metricsIterations = 50000
        
        val metricsTime = measureTime {
            repeat(metricsIterations) { i ->
                metricsCollector.recordThroughput("test-metric", 1)
                metricsCollector.recordTradeLatency("test-latency", i.toLong() * 1000)
            }
        }
        
        println("\n📊 指标收集性能:")
        println("  执行时间: $metricsTime")
        println("  指标数量: $metricsIterations")
        println("  收集速度: ${String.format("%.2f", metricsIterations.toDouble() / metricsTime.inWholeMilliseconds * 1000)} metrics/sec")
        
        // 4. 简单事件处理性能
        val eventCounter = AtomicLong(0)
        val eventIterations = 100000
        
        val eventTime = measureTime {
            repeat(eventIterations) { i ->
                // 模拟事件处理
                val event = "event-$i"
                val hash = event.hashCode()
                eventCounter.incrementAndGet()
            }
        }
        
        println("\n⚡ 事件处理性能:")
        println("  执行时间: $eventTime")
        println("  事件数量: $eventIterations")
        println("  处理速度: ${String.format("%.2f", eventIterations.toDouble() / eventTime.inWholeMilliseconds * 1000)} events/sec")
        
        // 5. 性能总结
        println("\n🎯 性能总结:")
        val memoryOpsPerSec = iterations.toDouble() / memoryTime.inWholeMilliseconds * 1000
        val metricsPerSec = metricsIterations.toDouble() / metricsTime.inWholeMilliseconds * 1000
        val eventsPerSec = eventIterations.toDouble() / eventTime.inWholeMilliseconds * 1000
        
        println("  内存操作: ${String.format("%,.0f", memoryOpsPerSec)} ops/sec")
        println("  指标收集: ${String.format("%,.0f", metricsPerSec)} metrics/sec")
        println("  事件处理: ${String.format("%,.0f", eventsPerSec)} events/sec")
        
        // 6. 性能等级评估
        println("\n🏆 性能等级:")
        when {
            eventsPerSec >= 1_000_000 -> println("  🥇 优秀 (>1M events/sec)")
            eventsPerSec >= 500_000 -> println("  🥈 良好 (>500K events/sec)")
            eventsPerSec >= 100_000 -> println("  🥉 合格 (>100K events/sec)")
            else -> println("  ⚠️ 需要优化 (<100K events/sec)")
        }
        
        when {
            memoryStats.poolHitRate >= 0.95 -> println("  🥇 内存管理优秀 (>95% 命中率)")
            memoryStats.poolHitRate >= 0.80 -> println("  🥈 内存管理良好 (>80% 命中率)")
            memoryStats.poolHitRate >= 0.60 -> println("  🥉 内存管理合格 (>60% 命中率)")
            else -> println("  ⚠️ 内存管理需要优化 (<60% 命中率)")
        }
        
        // 清理
        memoryManager.cleanup()
        
        println("\n✅ 快速性能验证完成")
    }
    
    @Test
    fun `延迟基准测试`() {
        println("=== 延迟基准测试 ===")
        
        val iterations = 10000
        val latencies = mutableListOf<Long>()
        
        repeat(iterations) {
            val start = System.nanoTime()
            
            // 模拟简单的事件处理
            val event = "latency-test-$it"
            val processed = event.hashCode()
            
            val end = System.nanoTime()
            latencies.add(end - start)
        }
        
        // 计算延迟统计
        val sortedLatencies = latencies.sorted()
        val p50 = sortedLatencies[sortedLatencies.size * 50 / 100]
        val p95 = sortedLatencies[sortedLatencies.size * 95 / 100]
        val p99 = sortedLatencies[sortedLatencies.size * 99 / 100]
        val max = sortedLatencies.last()
        val avg = latencies.average().toLong()
        
        println("\n⏱️ 延迟统计 (纳秒):")
        println("  平均: ${String.format("%,d", avg)} ns (${String.format("%.2f", avg / 1000.0)} μs)")
        println("  P50:  ${String.format("%,d", p50)} ns (${String.format("%.2f", p50 / 1000.0)} μs)")
        println("  P95:  ${String.format("%,d", p95)} ns (${String.format("%.2f", p95 / 1000.0)} μs)")
        println("  P99:  ${String.format("%,d", p99)} ns (${String.format("%.2f", p99 / 1000.0)} μs)")
        println("  最大: ${String.format("%,d", max)} ns (${String.format("%.2f", max / 1000.0)} μs)")
        
        // 延迟等级评估
        println("\n🎯 延迟等级:")
        when {
            p99 < 1000 -> println("  🥇 超低延迟 (P99 < 1μs)")
            p99 < 10000 -> println("  🥈 低延迟 (P99 < 10μs)")
            p99 < 100000 -> println("  🥉 中等延迟 (P99 < 100μs)")
            else -> println("  ⚠️ 高延迟 (P99 > 100μs)")
        }
        
        println("\n✅ 延迟基准测试完成")
    }
    
    @Test
    fun `吞吐量基准测试`() {
        println("=== 吞吐量基准测试 ===")
        
        val testDurationMs = 5000L // 5秒测试
        val startTime = System.currentTimeMillis()
        var operations = 0L
        
        while (System.currentTimeMillis() - startTime < testDurationMs) {
            // 模拟高频操作
            val event = "throughput-test-$operations"
            val hash = event.hashCode()
            operations++
        }
        
        val actualDuration = System.currentTimeMillis() - startTime
        val throughput = operations.toDouble() / actualDuration * 1000
        
        println("\n🚀 吞吐量结果:")
        println("  测试时长: ${actualDuration} ms")
        println("  总操作数: ${String.format("%,d", operations)}")
        println("  吞吐量: ${String.format("%,.0f", throughput)} ops/sec")
        
        // 吞吐量等级评估
        println("\n🎯 吞吐量等级:")
        when {
            throughput >= 10_000_000 -> println("  🥇 超高吞吐量 (>10M ops/sec)")
            throughput >= 1_000_000 -> println("  🥈 高吞吐量 (>1M ops/sec)")
            throughput >= 100_000 -> println("  🥉 中等吞吐量 (>100K ops/sec)")
            else -> println("  ⚠️ 低吞吐量 (<100K ops/sec)")
        }
        
        println("\n✅ 吞吐量基准测试完成")
    }
}
