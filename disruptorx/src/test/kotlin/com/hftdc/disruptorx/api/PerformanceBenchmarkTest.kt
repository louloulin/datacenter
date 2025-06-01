package com.hftdc.disruptorx.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 性能基准测试 - 验证api.md中的性能指标
 */
class PerformanceBenchmarkTest {

    data class BenchmarkEvent(val id: Long, val timestamp: Long, val data: String)

    @Test
    fun `性能基准测试 - 本地高吞吐量`() {
        println("\n=== 性能基准测试: 本地高吞吐量 ===")
        
        val eventCount = 100_000 // 10万事件
        val processedCount = AtomicLong(0)
        val latch = CountDownLatch(eventCount)
        
        // 高性能配置
        val bus = eventBus<BenchmarkEvent> {
            ringBufferSize = 65536 // 64K缓冲区
            waitStrategy = WaitStrategy.BUSY_SPIN // 最高性能
            producerType = ProducerType.SINGLE // 单生产者优化
            
            performance {
                batchSize = 1000
                enableZeroCopy = true
                objectPoolSize = 10000
            }
        }
        
        bus.on { event ->
            processedCount.incrementAndGet()
            latch.countDown()
        }
        
        bus.start()
        
        val startTime = System.nanoTime()
        
        // 发布大量事件
        repeat(eventCount) { i ->
            bus.emit(BenchmarkEvent(
                id = i.toLong(),
                timestamp = System.nanoTime(),
                data = "benchmark-data-$i"
            ))
        }
        
        // 等待处理完成
        val completed = latch.await(30, TimeUnit.SECONDS)
        val endTime = System.nanoTime()
        
        val durationMs = (endTime - startTime) / 1_000_000
        val throughput = (eventCount * 1000.0) / durationMs
        
        println("性能测试结果:")
        println("  事件数量: $eventCount")
        println("  处理完成: ${processedCount.get()}")
        println("  耗时: ${durationMs}ms")
        println("  吞吐量: ${String.format("%.0f", throughput)} events/sec")
        println("  平均延迟: ${String.format("%.2f", durationMs.toDouble() / eventCount)}ms/event")
        
        bus.close()
        
        assert(completed) { "所有事件应该在30秒内处理完成" }
        assert(processedCount.get() == eventCount.toLong()) { "应该处理所有事件" }
        assert(throughput > 10_000) { "吞吐量应该超过1万events/sec" }
        
        println("✅ 本地高吞吐量测试通过")
    }

    @Test
    fun `性能基准测试 - 低延迟配置`() {
        println("\n=== 性能基准测试: 低延迟配置 ===")
        
        val eventCount = 10_000 // 1万事件，专注于延迟
        val latencies = mutableListOf<Long>()
        val latch = CountDownLatch(eventCount)
        
        // 低延迟配置
        val bus = eventBus<BenchmarkEvent> {
            ringBufferSize = 1024 // 较小缓冲区减少延迟
            waitStrategy = WaitStrategy.BUSY_SPIN
            producerType = ProducerType.SINGLE
        }
        
        bus.on { event ->
            val latency = System.nanoTime() - event.timestamp
            synchronized(latencies) {
                latencies.add(latency)
            }
            latch.countDown()
        }
        
        bus.start()
        
        // 发布事件并测量延迟
        repeat(eventCount) { i ->
            val timestamp = System.nanoTime()
            bus.emit(BenchmarkEvent(
                id = i.toLong(),
                timestamp = timestamp,
                data = "latency-test-$i"
            ))
        }
        
        val completed = latch.await(10, TimeUnit.SECONDS)
        bus.close()
        
        // 计算延迟统计
        val sortedLatencies = latencies.sorted()
        val avgLatency = latencies.average()
        val p50Latency = sortedLatencies[sortedLatencies.size * 50 / 100]
        val p95Latency = sortedLatencies[sortedLatencies.size * 95 / 100]
        val p99Latency = sortedLatencies[sortedLatencies.size * 99 / 100]
        
        println("延迟测试结果:")
        println("  事件数量: $eventCount")
        println("  平均延迟: ${String.format("%.2f", avgLatency / 1000)}μs")
        println("  P50延迟: ${String.format("%.2f", p50Latency / 1000.0)}μs")
        println("  P95延迟: ${String.format("%.2f", p95Latency / 1000.0)}μs")
        println("  P99延迟: ${String.format("%.2f", p99Latency / 1000.0)}μs")
        
        assert(completed) { "所有事件应该在10秒内处理完成" }
        assert(avgLatency < 100_000) { "平均延迟应该小于100μs" } // 100微秒
        
        println("✅ 低延迟配置测试通过")
    }

    @Test
    fun `性能基准测试 - 多生产者配置`() {
        println("\n=== 性能基准测试: 多生产者配置 ===")

        val eventCount = 50_000
        val processedCount = AtomicLong(0)
        val latch = CountDownLatch(eventCount)

        // 多生产者配置
        val bus = eventBus<BenchmarkEvent> {
            ringBufferSize = 32768
            waitStrategy = WaitStrategy.YIELDING
            producerType = ProducerType.MULTI // 多生产者
        }

        bus.on { event ->
            processedCount.incrementAndGet()
            latch.countDown()
        }

        bus.start()

        val startTime = System.nanoTime()

        // 模拟多个生产者（简化版本）
        repeat(eventCount) { i ->
            bus.emit(BenchmarkEvent(
                id = i.toLong(),
                timestamp = System.nanoTime(),
                data = "multi-producer-event-$i"
            ))
        }

        // 等待所有事件处理完成
        val completed = latch.await(30, TimeUnit.SECONDS)
        val endTime = System.nanoTime()

        val durationMs = (endTime - startTime) / 1_000_000
        val throughput = (eventCount * 1000.0) / durationMs

        println("多生产者测试结果:")
        println("  事件数量: $eventCount")
        println("  处理完成: ${processedCount.get()}")
        println("  耗时: ${durationMs}ms")
        println("  吞吐量: ${String.format("%.0f", throughput)} events/sec")

        bus.close()

        assert(completed) { "所有事件应该在30秒内处理完成" }
        assert(processedCount.get() == eventCount.toLong()) { "应该处理所有事件" }

        println("✅ 多生产者配置测试通过")
    }

    @Test
    fun `性能基准测试 - 内存使用优化`() {
        println("\n=== 性能基准测试: 内存使用优化 ===")
        
        val eventCount = 50_000
        val processedCount = AtomicLong(0)
        val latch = CountDownLatch(eventCount)
        
        // 内存优化配置
        val bus = eventBus<BenchmarkEvent> {
            ringBufferSize = 8192 // 较小的缓冲区
            waitStrategy = WaitStrategy.YIELDING
            
            performance {
                enableZeroCopy = true
                objectPoolSize = 5000 // 对象池优化
            }
        }
        
        bus.on { event ->
            processedCount.incrementAndGet()
            latch.countDown()
        }
        
        bus.start()
        
        // 记录内存使用
        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()
        
        val startTime = System.nanoTime()
        
        // 发布事件
        repeat(eventCount) { i ->
            bus.emit(BenchmarkEvent(
                id = i.toLong(),
                timestamp = System.nanoTime(),
                data = "memory-test-$i"
            ))
        }
        
        val completed = latch.await(20, TimeUnit.SECONDS)
        val endTime = System.nanoTime()
        
        // 强制GC并测量内存
        System.gc()
        Thread.sleep(100)
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        
        val durationMs = (endTime - startTime) / 1_000_000
        val throughput = (eventCount * 1000.0) / durationMs
        val memoryUsed = (finalMemory - initialMemory) / 1024 / 1024 // MB
        
        println("内存优化测试结果:")
        println("  事件数量: $eventCount")
        println("  处理完成: ${processedCount.get()}")
        println("  耗时: ${durationMs}ms")
        println("  吞吐量: ${String.format("%.0f", throughput)} events/sec")
        println("  内存使用: ${memoryUsed}MB")
        println("  每事件内存: ${String.format("%.2f", memoryUsed * 1024.0 / eventCount)}KB")
        
        bus.close()
        
        assert(completed) { "所有事件应该在20秒内处理完成" }
        assert(processedCount.get() == eventCount.toLong()) { "应该处理所有事件" }
        
        println("✅ 内存使用优化测试通过")
    }

    @Test
    fun `性能基准测试 - 不同等待策略对比`() {
        println("\n=== 性能基准测试: 等待策略对比 ===")
        
        val eventCount = 20_000
        val strategies = listOf(
            "BLOCKING" to WaitStrategy.BLOCKING,
            "YIELDING" to WaitStrategy.YIELDING,
            "BUSY_SPIN" to WaitStrategy.BUSY_SPIN,
            "SLEEPING" to WaitStrategy.SLEEPING
        )
        
        strategies.forEach { (name, strategy) ->
            println("\n测试等待策略: $name")
            
            val processedCount = AtomicLong(0)
            val latch = CountDownLatch(eventCount)
            
            val bus = eventBus<BenchmarkEvent> {
                ringBufferSize = 4096
                waitStrategy = strategy
                producerType = ProducerType.SINGLE
            }
            
            bus.on { event ->
                processedCount.incrementAndGet()
                latch.countDown()
            }
            
            bus.start()
            
            val startTime = System.nanoTime()
            
            repeat(eventCount) { i ->
                bus.emit(BenchmarkEvent(
                    id = i.toLong(),
                    timestamp = System.nanoTime(),
                    data = "strategy-test-$i"
                ))
            }
            
            val completed = latch.await(15, TimeUnit.SECONDS)
            val endTime = System.nanoTime()
            
            val durationMs = (endTime - startTime) / 1_000_000
            val throughput = (eventCount * 1000.0) / durationMs
            
            println("  耗时: ${durationMs}ms")
            println("  吞吐量: ${String.format("%.0f", throughput)} events/sec")
            println("  处理完成: ${processedCount.get()}/${eventCount}")
            
            bus.close()
            
            assert(completed) { "$name 策略应该能处理完所有事件" }
        }
        
        println("✅ 等待策略对比测试通过")
    }
}
