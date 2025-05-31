package com.hftdc.disruptorx.benchmark

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.monitoring.TradingMetricsCollector
import com.hftdc.disruptorx.performance.EnhancedMemoryManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

/**
 * DisruptorX性能基准测试
 */
@OptIn(ExperimentalTime::class)
class PerformanceBenchmark {
    
    private val eventCounter = AtomicLong(0)
    private val processedCounter = AtomicLong(0)
    private lateinit var metricsCollector: TradingMetricsCollector
    private lateinit var memoryManager: EnhancedMemoryManager
    
    /**
     * 运行完整的性能基准测试
     */
    suspend fun runBenchmarks() {
        println("=== DisruptorX 性能基准测试 ===")
        
        // 初始化组件
        metricsCollector = TradingMetricsCollector()
        memoryManager = EnhancedMemoryManager()
        
        try {
            // 1. 单节点吞吐量测试
            runThroughputBenchmark()
            
            // 2. 延迟基准测试
            runLatencyBenchmark()
            
            // 3. 内存管理性能测试
            runMemoryBenchmark()
            
            // 4. 并发性能测试
            runConcurrencyBenchmark()
            
            // 5. 综合压力测试
            runStressTest()
            
        } finally {
            memoryManager.cleanup()
        }
    }
    
    /**
     * 吞吐量基准测试
     */
    private suspend fun runThroughputBenchmark() {
        println("\n--- 吞吐量基准测试 ---")
        
        val config = DisruptorXConfig(
            nodeId = "benchmark-node",
            host = "localhost",
            port = 9091,
            nodeRole = NodeRole.MIXED,
            eventBatchSize = 1000,
            eventBatchTimeWindowMillis = 1
        )
        
        val node = DisruptorX.createNode(config)
        
        try {
            node.initialize()
            
            // 设置事件处理器
            node.eventBus.subscribe("benchmark.events") { event ->
                processedCounter.incrementAndGet()
                // 模拟轻量级处理
                val data = event as String
                data.length // 简单操作
            }
            
            val testDuration = 10.seconds
            val eventsToSend = 1_000_000
            
            println("发送 $eventsToSend 个事件，测试时长 $testDuration")
            
            val actualTime = measureTime {
                val job = CoroutineScope(Dispatchers.Default).launch {
                    repeat(eventsToSend) { i ->
                        val event = "benchmark-event-$i"
                        node.eventBus.publish(event, "benchmark.events")
                        eventCounter.incrementAndGet()
                        
                        if (i % 100000 == 0) {
                            yield() // 让出CPU时间
                        }
                    }
                }
                
                job.join()
                
                // 等待所有事件处理完成
                while (processedCounter.get() < eventsToSend) {
                    delay(100)
                }
            }
            
            val throughput = eventsToSend.toDouble() / actualTime.inWholeSeconds
            println("实际处理时间: $actualTime")
            println("发送事件数: ${eventCounter.get()}")
            println("处理事件数: ${processedCounter.get()}")
            println("吞吐量: ${String.format("%.2f", throughput)} events/sec")
            
        } finally {
            node.shutdown()
            eventCounter.set(0)
            processedCounter.set(0)
        }
    }
    
    /**
     * 延迟基准测试
     */
    private suspend fun runLatencyBenchmark() {
        println("\n--- 延迟基准测试 ---")
        
        val config = DisruptorXConfig(
            nodeId = "latency-node",
            host = "localhost",
            port = 9092,
            nodeRole = NodeRole.MIXED,
            eventBatchSize = 1
        )
        
        val node = DisruptorX.createNode(config)
        
        try {
            node.initialize()
            
            val latencies = mutableListOf<Long>()
            val testEvents = 10000
            
            // 设置延迟测量处理器
            node.eventBus.subscribe("latency.test") { event ->
                val data = event as LatencyTestEvent
                val latency = System.nanoTime() - data.timestamp
                latencies.add(latency)
                metricsCollector.recordTradeLatency("latency-test", latency)
            }
            
            println("发送 $testEvents 个事件进行延迟测试")
            
            repeat(testEvents) { i ->
                val event = LatencyTestEvent(
                    id = i,
                    timestamp = System.nanoTime()
                )
                node.eventBus.publish(event, "latency.test")
                
                // 小间隔以避免批处理效应
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
            val p999 = sortedLatencies[sortedLatencies.size * 999 / 1000]
            val max = sortedLatencies.last()
            
            println("延迟统计 (纳秒):")
            println("  P50:  ${String.format("%,d", p50)} ns (${String.format("%.2f", p50 / 1000.0)} μs)")
            println("  P95:  ${String.format("%,d", p95)} ns (${String.format("%.2f", p95 / 1000.0)} μs)")
            println("  P99:  ${String.format("%,d", p99)} ns (${String.format("%.2f", p99 / 1000.0)} μs)")
            println("  P99.9: ${String.format("%,d", p999)} ns (${String.format("%.2f", p999 / 1000.0)} μs)")
            println("  Max:  ${String.format("%,d", max)} ns (${String.format("%.2f", max / 1000.0)} μs)")
            
        } finally {
            node.shutdown()
        }
    }
    
    /**
     * 内存管理性能测试
     */
    private suspend fun runMemoryBenchmark() {
        println("\n--- 内存管理性能测试 ---")
        
        val iterations = 100000
        
        // 测试ByteBuffer分配性能
        val bufferTime = measureTime {
            repeat(iterations) {
                val buffer = memoryManager.acquireByteBuffer(1024)
                try {
                    buffer.putInt(Random.nextInt())
                    buffer.flip()
                    buffer.getInt()
                } finally {
                    memoryManager.releaseByteBuffer(buffer)
                }
            }
        }

        // 测试StringBuilder性能
        val stringBuilderTime = measureTime {
            repeat(iterations) {
                val sb = memoryManager.acquireStringBuilder()
                try {
                    sb.append("test-").append(Random.nextInt())
                    sb.toString()
                } finally {
                    memoryManager.releaseStringBuilder(sb)
                }
            }
        }
        
        val memoryStats = memoryManager.getMemoryStats()
        
        println("内存管理性能:")
        println("  ByteBuffer操作 ($iterations 次): $bufferTime")
        println("  StringBuilder操作 ($iterations 次): $stringBuilderTime")
        println("  池命中率: ${String.format("%.2f", memoryStats.poolHitRate * 100)}%")
        println("  总分配次数: ${memoryStats.totalAllocations}")
        println("  总释放次数: ${memoryStats.totalDeallocations}")
    }
    
    /**
     * 并发性能测试
     */
    private suspend fun runConcurrencyBenchmark() {
        println("\n--- 并发性能测试 ---")
        
        val config = DisruptorXConfig(
            nodeId = "concurrent-node",
            host = "localhost",
            port = 9093,
            nodeRole = NodeRole.MIXED,
            eventBatchSize = 100
        )
        
        val node = DisruptorX.createNode(config)
        
        try {
            node.initialize()
            
            val concurrentProducers = 10
            val eventsPerProducer = 10000
            val totalEvents = concurrentProducers * eventsPerProducer
            
            node.eventBus.subscribe("concurrent.test") { event ->
                processedCounter.incrementAndGet()
                // 模拟处理工作
                val data = event as String
                data.hashCode()
            }
            
            println("启动 $concurrentProducers 个并发生产者，每个发送 $eventsPerProducer 个事件")
            
            val concurrentTime = measureTime {
                val jobs = (1..concurrentProducers).map { producerId ->
                    CoroutineScope(Dispatchers.Default).launch {
                        repeat(eventsPerProducer) { eventId ->
                            val event = "producer-$producerId-event-$eventId"
                            node.eventBus.publish(event, "concurrent.test")
                            eventCounter.incrementAndGet()
                        }
                    }
                }
                
                jobs.forEach { it.join() }
                
                // 等待所有事件处理完成
                while (processedCounter.get() < totalEvents) {
                    delay(100)
                }
            }
            
            val concurrentThroughput = totalEvents.toDouble() / concurrentTime.inWholeSeconds
            println("并发测试结果:")
            println("  处理时间: $concurrentTime")
            println("  总事件数: $totalEvents")
            println("  并发吞吐量: ${String.format("%.2f", concurrentThroughput)} events/sec")
            
        } finally {
            node.shutdown()
            eventCounter.set(0)
            processedCounter.set(0)
        }
    }
    
    /**
     * 综合压力测试
     */
    private suspend fun runStressTest() {
        println("\n--- 综合压力测试 ---")
        
        val config = DisruptorXConfig(
            nodeId = "stress-node",
            host = "localhost",
            port = 9094,
            nodeRole = NodeRole.MIXED,
            eventBatchSize = 500,
            maxConcurrentWorkflows = 100
        )
        
        val node = DisruptorX.createNode(config)
        
        try {
            node.initialize()
            
            // 设置多个主题的处理器
            val topics = listOf("stress.topic1", "stress.topic2", "stress.topic3")
            topics.forEach { topic ->
                node.eventBus.subscribe(topic) { event ->
                    processedCounter.incrementAndGet()
                    // 模拟复杂处理
                    val data = event as StressTestEvent
                    data.payload.hashCode()
                    Thread.sleep(0, Random.nextInt(1000)) // 0-1微秒的随机延迟
                }
            }
            
            val testDuration = 30.seconds
            println("运行 $testDuration 的压力测试")
            
            val stressTime = measureTime {
                val jobs = topics.map { topic ->
                    CoroutineScope(Dispatchers.Default).launch {
                        val endTime = System.currentTimeMillis() + testDuration.inWholeMilliseconds
                        var eventId = 0
                        
                        while (System.currentTimeMillis() < endTime) {
                            val event = StressTestEvent(
                                id = eventId++,
                                topic = topic,
                                payload = "stress-test-data-${Random.nextInt()}"
                            )
                            node.eventBus.publish(event, topic)
                            eventCounter.incrementAndGet()
                            
                            // 随机间隔
                            delay(Random.nextLong(1, 10))
                        }
                    }
                }
                
                jobs.forEach { it.join() }
                delay(5.seconds) // 等待处理完成
            }
            
            val stressThroughput = processedCounter.get().toDouble() / stressTime.inWholeSeconds
            println("压力测试结果:")
            println("  测试时间: $stressTime")
            println("  发送事件: ${eventCounter.get()}")
            println("  处理事件: ${processedCounter.get()}")
            println("  平均吞吐量: ${String.format("%.2f", stressThroughput)} events/sec")
            
        } finally {
            node.shutdown()
        }
    }
}

/**
 * 延迟测试事件
 */
data class LatencyTestEvent(
    val id: Int,
    val timestamp: Long
)

/**
 * 压力测试事件
 */
data class StressTestEvent(
    val id: Int,
    val topic: String,
    val payload: String
)

/**
 * 主函数
 */
suspend fun main() {
    val benchmark = PerformanceBenchmark()
    benchmark.runBenchmarks()
}
