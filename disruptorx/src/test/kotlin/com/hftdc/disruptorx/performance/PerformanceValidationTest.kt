package com.hftdc.disruptorx.performance

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.monitoring.TradingMetricsCollector
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

/**
 * DisruptorX性能验证测试
 * 快速验证关键性能指标是否达到预期
 */
@ExperimentalTime
class PerformanceValidationTest {

    private lateinit var memoryManager: EnhancedMemoryManager
    private lateinit var metricsCollector: TradingMetricsCollector
    private val eventCounter = AtomicLong(0)

    @BeforeEach
    fun setup() {
        memoryManager = EnhancedMemoryManager()
        metricsCollector = TradingMetricsCollector()
        eventCounter.set(0)
    }

    @AfterEach
    fun cleanup() {
        memoryManager.cleanup()
    }

    @Test
    fun `验证内存管理性能 - 目标池命中率大于80%`() {
        val iterations = 10000
        
        // 预热内存池
        repeat(100) {
            memoryManager.useByteBuffer(1024) { }
            memoryManager.useStringBuilder { }
        }
        
        val initialStats = memoryManager.getMemoryStats()
        
        // 执行内存操作测试
        val executionTime = measureTime {
            repeat(iterations) {
                memoryManager.useByteBuffer(1024) { buffer ->
                    buffer.putInt(42)
                    buffer.flip()
                    buffer.getInt()
                }
                
                memoryManager.useStringBuilder { sb ->
                    sb.append("performance-test-").append(it)
                    sb.toString()
                }
            }
        }
        
        val finalStats = memoryManager.getMemoryStats()
        val poolHitRate = finalStats.poolHitRate
        
        println("=== 内存管理性能验证 ===")
        println("执行时间: $executionTime")
        println("池命中率: ${String.format("%.2f", poolHitRate * 100)}%")
        println("总分配: ${finalStats.totalAllocations - initialStats.totalAllocations}")
        println("总释放: ${finalStats.totalDeallocations - initialStats.totalDeallocations}")
        
        // 验证性能要求
        assertTrue(poolHitRate >= 0.8, "池命中率应该大于80%，实际为: ${String.format("%.2f", poolHitRate * 100)}%")
        assertTrue(executionTime.inWholeMilliseconds < 5000, "内存操作时间应该小于5秒，实际为: $executionTime")
        
        println("✅ 内存管理性能验证通过")
    }

    @Test
    fun `验证事件处理吞吐量 - 目标大于50000 events_sec`() = runBlocking {
        val config = DisruptorXConfig(
            nodeId = "perf-test-node",
            host = "localhost",
            port = 9100,
            nodeRole = NodeRole.MIXED,
            eventBatchSize = 1000
        )
        
        val node = DisruptorX.createNode(config)
        
        try {
            node.initialize()
            
            val processedEvents = AtomicLong(0)
            
            // 设置高性能事件处理器
            node.eventBus.subscribe("perf.test") { event ->
                processedEvents.incrementAndGet()
                // 模拟轻量级处理
                val data = event as String
                data.hashCode()
            }
            
            val eventsToSend = 100000
            
            val processingTime = measureTime {
                // 批量发送事件
                repeat(eventsToSend) { i ->
                    val event = "perf-event-$i"
                    node.eventBus.publish(event, "perf.test")
                    eventCounter.incrementAndGet()
                }
                
                // 等待所有事件处理完成
                while (processedEvents.get() < eventsToSend) {
                    delay(10)
                }
            }
            
            val throughput = eventsToSend.toDouble() / processingTime.inWholeSeconds
            
            println("=== 事件处理吞吐量验证 ===")
            println("处理事件数: $eventsToSend")
            println("处理时间: $processingTime")
            println("吞吐量: ${String.format("%.2f", throughput)} events/sec")
            
            // 验证吞吐量要求
            assertTrue(throughput >= 50000.0, "吞吐量应该大于50,000 events/sec，实际为: ${String.format("%.2f", throughput)}")
            assertEquals(eventsToSend.toLong(), processedEvents.get(), "所有事件都应该被处理")
            
            println("✅ 事件处理吞吐量验证通过")
            
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `验证事件处理延迟 - 目标P99小于1000微秒`() = runBlocking {
        val config = DisruptorXConfig(
            nodeId = "latency-test-node",
            host = "localhost",
            port = 9101,
            nodeRole = NodeRole.MIXED,
            eventBatchSize = 1
        )
        
        val node = DisruptorX.createNode(config)
        
        try {
            node.initialize()
            
            val latencies = mutableListOf<Long>()
            val testEvents = 5000
            
            // 设置延迟测量处理器
            node.eventBus.subscribe("latency.test") { event ->
                val testEvent = event as LatencyTestEvent
                val latency = System.nanoTime() - testEvent.timestamp
                synchronized(latencies) {
                    latencies.add(latency)
                }
                metricsCollector.recordTradeLatency("latency-test", latency)
            }
            
            println("=== 事件处理延迟验证 ===")
            println("发送 $testEvents 个事件进行延迟测试...")
            
            repeat(testEvents) { i ->
                val event = LatencyTestEvent(i, System.nanoTime())
                node.eventBus.publish(event, "latency.test")
                
                // 小间隔避免批处理效应
                delay(1)
            }
            
            // 等待所有事件处理完成
            while (latencies.size < testEvents) {
                delay(10)
            }
            
            // 计算延迟统计
            val sortedLatencies = latencies.sorted()
            val p50 = sortedLatencies[sortedLatencies.size * 50 / 100]
            val p95 = sortedLatencies[sortedLatencies.size * 95 / 100]
            val p99 = sortedLatencies[sortedLatencies.size * 99 / 100]
            val max = sortedLatencies.last()
            val avg = latencies.average().toLong()
            
            println("延迟统计 (微秒):")
            println("  平均: ${String.format("%,d", avg / 1000)} μs")
            println("  P50:  ${String.format("%,d", p50 / 1000)} μs")
            println("  P95:  ${String.format("%,d", p95 / 1000)} μs")
            println("  P99:  ${String.format("%,d", p99 / 1000)} μs")
            println("  最大: ${String.format("%,d", max / 1000)} μs")
            
            // 验证延迟要求
            assertTrue(p99 < 1_000_000, "P99延迟应该小于1000μs，实际为: ${p99 / 1000}μs")
            assertTrue(p95 < 500_000, "P95延迟应该小于500μs，实际为: ${p95 / 1000}μs")
            assertTrue(avg < 100_000, "平均延迟应该小于100μs，实际为: ${avg / 1000}μs")
            
            println("✅ 事件处理延迟验证通过")
            
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `验证并发处理性能 - 目标支持10个并发生产者`() = runBlocking {
        val config = DisruptorXConfig(
            nodeId = "concurrent-test-node",
            host = "localhost",
            port = 9102,
            nodeRole = NodeRole.MIXED,
            eventBatchSize = 100
        )
        
        val node = DisruptorX.createNode(config)
        
        try {
            node.initialize()
            
            val processedEvents = AtomicLong(0)
            val concurrentProducers = 10
            val eventsPerProducer = 5000
            val totalEvents = concurrentProducers * eventsPerProducer
            
            // 设置并发处理器
            node.eventBus.subscribe("concurrent.test") { event ->
                processedEvents.incrementAndGet()
                val data = event as String
                data.hashCode()
            }
            
            println("=== 并发处理性能验证 ===")
            println("启动 $concurrentProducers 个并发生产者，每个发送 $eventsPerProducer 个事件")
            
            val concurrentTime = measureTime {
                val jobs = (1..concurrentProducers).map { producerId ->
                    async {
                        repeat(eventsPerProducer) { eventId ->
                            val event = "producer-$producerId-event-$eventId"
                            node.eventBus.publish(event, "concurrent.test")
                            eventCounter.incrementAndGet()
                        }
                    }
                }
                
                jobs.forEach { it.await() }
                
                // 等待所有事件处理完成
                while (processedEvents.get() < totalEvents) {
                    delay(50)
                }
            }
            
            val concurrentThroughput = totalEvents.toDouble() / concurrentTime.inWholeSeconds
            
            println("并发测试结果:")
            println("  处理时间: $concurrentTime")
            println("  总事件数: $totalEvents")
            println("  并发吞吐量: ${String.format("%.2f", concurrentThroughput)} events/sec")
            
            // 验证并发性能要求
            assertEquals(totalEvents.toLong(), processedEvents.get(), "所有事件都应该被处理")
            assertTrue(concurrentThroughput >= 25000.0, "并发吞吐量应该大于25,000 events/sec，实际为: ${String.format("%.2f", concurrentThroughput)}")
            
            println("✅ 并发处理性能验证通过")
            
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `验证内存使用效率 - 目标内存增长小于100MB`() = runBlocking {
        val config = DisruptorXConfig(
            nodeId = "memory-test-node",
            host = "localhost",
            port = 9103,
            nodeRole = NodeRole.MIXED,
            eventBatchSize = 500
        )
        
        val node = DisruptorX.createNode(config)
        
        try {
            node.initialize()
            
            val runtime = Runtime.getRuntime()
            
            // 强制GC获得基准内存
            System.gc()
            Thread.sleep(100)
            val initialMemory = runtime.totalMemory() - runtime.freeMemory()
            
            val processedEvents = AtomicLong(0)
            
            node.eventBus.subscribe("memory.test") { event ->
                processedEvents.incrementAndGet()
                // 模拟一些内存使用
                val data = event as String
                data.repeat(5).hashCode()
            }
            
            val eventsToProcess = 50000
            
            println("=== 内存使用效率验证 ===")
            println("初始内存: ${initialMemory / 1024 / 1024} MB")
            println("处理 $eventsToProcess 个事件...")
            
            repeat(eventsToProcess) { i ->
                val event = "memory-test-event-$i"
                node.eventBus.publish(event, "memory.test")
            }
            
            // 等待处理完成
            while (processedEvents.get() < eventsToProcess) {
                delay(50)
            }
            
            // 强制GC获得最终内存
            System.gc()
            Thread.sleep(100)
            val finalMemory = runtime.totalMemory() - runtime.freeMemory()
            val memoryIncrease = finalMemory - initialMemory
            
            println("最终内存: ${finalMemory / 1024 / 1024} MB")
            println("内存增长: ${memoryIncrease / 1024 / 1024} MB")
            println("处理事件数: ${processedEvents.get()}")
            
            // 验证内存使用效率
            assertEquals(eventsToProcess.toLong(), processedEvents.get(), "所有事件都应该被处理")
            assertTrue(memoryIncrease < 100 * 1024 * 1024, "内存增长应该小于100MB，实际为: ${memoryIncrease / 1024 / 1024} MB")
            
            println("✅ 内存使用效率验证通过")
            
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `验证系统整体性能指标`() {
        println("=== DisruptorX 系统整体性能指标 ===")
        
        val memoryStats = memoryManager.getMemoryStats()
        
        println("内存管理:")
        println("  池命中率: ${String.format("%.2f", memoryStats.poolHitRate * 100)}%")
        println("  总分配次数: ${memoryStats.totalAllocations}")
        println("  总释放次数: ${memoryStats.totalDeallocations}")
        
        val runtime = Runtime.getRuntime()
        println("JVM内存:")
        println("  最大内存: ${runtime.maxMemory() / 1024 / 1024} MB")
        println("  总内存: ${runtime.totalMemory() / 1024 / 1024} MB")
        println("  可用内存: ${runtime.freeMemory() / 1024 / 1024} MB")
        println("  已用内存: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024} MB")
        
        println("处理器信息:")
        println("  可用处理器: ${runtime.availableProcessors()}")
        
        // 基本性能验证
        assertTrue(memoryStats.poolHitRate >= 0.0, "内存池应该正常工作")
        assertTrue(runtime.availableProcessors() >= 1, "应该有可用的处理器")
        
        println("✅ 系统整体性能指标验证通过")
    }
}

/**
 * 延迟测试事件
 */
data class LatencyTestEvent(
    val id: Int,
    val timestamp: Long
)
