package com.hftdc.disruptorx.performance

import com.lmax.disruptor.BlockingWaitStrategy
import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.RingBuffer
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.HdrHistogram.Histogram
import com.hftdc.disruptorx.util.EventObjectPool
import com.hftdc.disruptorx.util.OffHeapBuffer
import com.hftdc.disruptorx.util.PaddedSequence
import com.hftdc.disruptorx.util.ThreadAffinity
import java.nio.ByteBuffer
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 性能优化基准测试
 * 对比使用和不使用各种优化组件的性能
 */
@Tag("benchmark") // 标记为基准测试，可选择性运行
class PerformanceOptimizationBenchmark {
    
    // 基准测试配置
    private val ringBufferSize = 1024 * 64 // 64K
    private val eventCount = 10_000_000 // 1千万事件
    private val warmupCount = 1_000_000 // 100万预热
    private val producerCount = 4
    private val consumerCount = 4
    
    // 延迟直方图 (纳秒精度)
    private lateinit var baselineLatencyHistogram: Histogram
    private lateinit var optimizedLatencyHistogram: Histogram
    
    // 性能测试事件
    data class BenchmarkEvent(
        var timestamp: Long = 0,
        var value: Long = 0,
        var data: ByteArray = ByteArray(0)
    ) {
        // 重写equals和hashCode，因为data是数组
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as BenchmarkEvent
            
            if (timestamp != other.timestamp) return false
            if (value != other.value) return false
            if (!data.contentEquals(other.data)) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + value.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
    
    // 基准测试事件工厂
    class BenchmarkEventFactory : EventFactory<BenchmarkEvent> {
        override fun newInstance(): BenchmarkEvent = BenchmarkEvent()
    }
    
    @BeforeEach
    fun setUp() {
        // 创建延迟直方图，精度为3位有效数字，范围从1ns到10s
        baselineLatencyHistogram = Histogram(TimeUnit.SECONDS.toNanos(10), 3)
        optimizedLatencyHistogram = Histogram(TimeUnit.SECONDS.toNanos(10), 3)
    }
    
    @AfterEach
    fun tearDown() {
        // 输出延迟统计
        println("=== 基准测试延迟统计 (微秒) ===")
        println("基线版本:")
        println("  中位数延迟: ${baselineLatencyHistogram.getValueAtPercentile(50.0) / 1000.0} 微秒")
        println("  90%延迟: ${baselineLatencyHistogram.getValueAtPercentile(90.0) / 1000.0} 微秒")
        println("  99%延迟: ${baselineLatencyHistogram.getValueAtPercentile(99.0) / 1000.0} 微秒")
        println("  99.9%延迟: ${baselineLatencyHistogram.getValueAtPercentile(99.9) / 1000.0} 微秒")
        println("  最大延迟: ${baselineLatencyHistogram.maxValue / 1000.0} 微秒")
        
        println("优化版本:")
        println("  中位数延迟: ${optimizedLatencyHistogram.getValueAtPercentile(50.0) / 1000.0} 微秒")
        println("  90%延迟: ${optimizedLatencyHistogram.getValueAtPercentile(90.0) / 1000.0} 微秒")
        println("  99%延迟: ${optimizedLatencyHistogram.getValueAtPercentile(99.0) / 1000.0} 微秒")
        println("  99.9%延迟: ${optimizedLatencyHistogram.getValueAtPercentile(99.9) / 1000.0} 微秒")
        println("  最大延迟: ${optimizedLatencyHistogram.maxValue / 1000.0} 微秒")
        
        // 计算性能提升
        val p50Improvement = (baselineLatencyHistogram.getValueAtPercentile(50.0) - 
                             optimizedLatencyHistogram.getValueAtPercentile(50.0)) * 100.0 / 
                             baselineLatencyHistogram.getValueAtPercentile(50.0)
        
        val p99Improvement = (baselineLatencyHistogram.getValueAtPercentile(99.0) - 
                             optimizedLatencyHistogram.getValueAtPercentile(99.0)) * 100.0 / 
                             baselineLatencyHistogram.getValueAtPercentile(99.0)
        
        println("性能提升:")
        println("  中位数延迟改进: ${p50Improvement.toInt()}%")
        println("  99%延迟改进: ${p99Improvement.toInt()}%")
    }
    
    /**
     * 基线测试 - 标准Disruptor实现
     */
    private fun runBaselineBenchmark() {
        // 创建标准线程工厂
        val threadFactory = ThreadFactory { r -> Thread(r, "baseline-thread") }
        
        // 创建标准Disruptor
        val disruptor = Disruptor(
            BenchmarkEventFactory(),
            ringBufferSize,
            threadFactory,
            ProducerType.MULTI,
            BlockingWaitStrategy()
        )
        
        // 设置事件处理器
        val completedCount = AtomicLong(0)
        val handler = EventHandler<BenchmarkEvent> { event, _, _ ->
            val latency = System.nanoTime() - event.timestamp
            baselineLatencyHistogram.recordValue(latency)
            completedCount.incrementAndGet()
        }
        
        // 添加消费者
        for (i in 0 until consumerCount) {
            disruptor.handleEventsWith(handler)
        }
        
        // 启动Disruptor
        disruptor.start()
        val ringBuffer = disruptor.ringBuffer
        
        try {
            // 预热
            runProducers(ringBuffer, warmupCount, null)
            // 等待预热完成
            while (completedCount.get() < warmupCount) {
                Thread.sleep(100)
            }
            
            // 重置计数
            completedCount.set(0)
            baselineLatencyHistogram.reset()
            
            // 运行实际测试
            val startTime = System.nanoTime()
            runProducers(ringBuffer, eventCount, null)
            
            // 等待所有事件处理完成
            while (completedCount.get() < eventCount) {
                Thread.sleep(100)
            }
            
            val endTime = System.nanoTime()
            val durationSeconds = (endTime - startTime) / 1_000_000_000.0
            
            println("基线测试完成: $eventCount 事件，耗时 $durationSeconds 秒")
            println("平均吞吐量: ${(eventCount / durationSeconds).toInt()} 事件/秒")
            
        } finally {
            disruptor.shutdown()
        }
    }
    
    /**
     * 优化测试 - 使用优化组件
     */
    private fun runOptimizedBenchmark() {
        // 1. 使用线程亲和性
        val cpuCores = ThreadAffinity.allocateCoresForCriticalProcessing(consumerCount + producerCount)
        val threadFactory = if (cpuCores.isNotEmpty()) {
            ThreadAffinity.newThreadFactory("optimized-thread", cpuCores)
        } else {
            ThreadAffinity.newThreadFactory("optimized-thread")
        }
        
        // 2. 使用对象池
        val eventPool = EventObjectPool<BenchmarkEvent>(
            ringBufferSize, // 初始池大小
            ringBufferSize * 2, // 最大池大小
            { BenchmarkEvent() }
        )
        
        // 3. 创建优化版Disruptor
        val disruptor = Disruptor(
            BenchmarkEventFactory(),
            ringBufferSize,
            threadFactory,
            ProducerType.MULTI,
            BlockingWaitStrategy()
        )
        
        // 4. 使用填充序列避免伪共享
        val completedSequence = PaddedSequence(0)
        val completedCount = AtomicLong(0)
        
        // 5. 创建堆外内存缓冲区用于高性能数据传输
        val offHeapBuffer = OffHeapBuffer.allocate(1024 * 1024) // 1MB
        
        // 设置事件处理器
        val handler = EventHandler<BenchmarkEvent> { event, _, _ ->
            try {
                // 记录延迟
                val latency = System.nanoTime() - event.timestamp
                optimizedLatencyHistogram.recordValue(latency)
                
                // 模拟处理，使用堆外内存
                if (event.data.isNotEmpty()) {
                    offHeapBuffer.write(event.data)
                }
                
                // 更新计数和序列
                completedCount.incrementAndGet()
                completedSequence.set(completedCount.get())
                
                // 将事件对象返回到对象池 (如果没有从对象池分配则跳过)
                eventPool.release(event)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // 添加消费者
        for (i in 0 until consumerCount) {
            disruptor.handleEventsWith(handler)
        }
        
        // 启动Disruptor
        disruptor.start()
        val ringBuffer = disruptor.ringBuffer
        
        try {
            // 预热
            runProducers(ringBuffer, warmupCount, eventPool)
            // 等待预热完成
            while (completedCount.get() < warmupCount) {
                Thread.sleep(100)
            }
            
            // 重置计数
            completedCount.set(0)
            completedSequence.set(0)
            optimizedLatencyHistogram.reset()
            offHeapBuffer.reset()
            
            // 运行实际测试
            val startTime = System.nanoTime()
            runProducers(ringBuffer, eventCount, eventPool)
            
            // 等待所有事件处理完成
            while (completedCount.get() < eventCount) {
                Thread.sleep(100)
            }
            
            val endTime = System.nanoTime()
            val durationSeconds = (endTime - startTime) / 1_000_000_000.0
            
            println("优化测试完成: $eventCount 事件，耗时 $durationSeconds 秒")
            println("平均吞吐量: ${(eventCount / durationSeconds).toInt()} 事件/秒")
            println("对象池统计: 创建 ${eventPool.getCreatedCount()}, 重用 ${eventPool.getReusedCount()}")
            
        } finally {
            disruptor.shutdown()
            offHeapBuffer.close()
        }
    }
    
    /**
     * 启动生产者线程
     */
    private fun runProducers(
        ringBuffer: RingBuffer<BenchmarkEvent>,
        eventCount: Int,
        eventPool: EventObjectPool<BenchmarkEvent>?
    ) {
        val eventsPerProducer = eventCount / producerCount
        val barrier = CyclicBarrier(producerCount)
        val running = AtomicBoolean(true)
        
        // 创建生产者线程池
        val executor = if (eventPool != null) {
            // 使用线程亲和性
            ThreadAffinity.newFixedThreadPool(producerCount, "producer")
        } else {
            Executors.newFixedThreadPool(producerCount)
        }
        
        for (i in 0 until producerCount) {
            executor.submit {
                val startSequence = i * eventsPerProducer
                val endSequence = startSequence + eventsPerProducer
                
                try {
                    // 随机数据生成
                    val random = java.util.Random()
                    val data = ByteArray(64) // 64字节的随机数据
                    
                    // 等待所有生产者准备好
                    barrier.await()
                    
                    for (j in startSequence until endSequence) {
                        if (!running.get()) break
                        
                        random.nextBytes(data)
                        
                        ringBuffer.publishEvent { event, _ ->
                            // 如果使用对象池，尝试从池中获取事件对象
                            val pooledEvent = eventPool?.acquire()
                            if (pooledEvent != null) {
                                // 使用对象池的对象
                                pooledEvent.timestamp = System.nanoTime()
                                pooledEvent.value = j.toLong()
                                pooledEvent.data = data.copyOf()
                                // 更新event引用
                                event.timestamp = pooledEvent.timestamp
                                event.value = pooledEvent.value
                                event.data = pooledEvent.data
                            } else {
                                // 直接使用Disruptor分配的对象
                                event.timestamp = System.nanoTime()
                                event.value = j.toLong()
                                event.data = data.copyOf()
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    running.set(false)
                }
            }
        }
        
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.MINUTES)
    }
    
    @Test
    fun `run performance comparison benchmark`() {
        println("=== 开始性能优化基准测试比较 ===")
        println("总事件数: $eventCount")
        println("预热事件数: $warmupCount")
        println("生产者: $producerCount, 消费者: $consumerCount")
        println("RingBuffer大小: $ringBufferSize")
        
        println("\n1. 运行基线测试...")
        runBaselineBenchmark()
        
        println("\n2. 运行优化测试...")
        runOptimizedBenchmark()
        
        println("\n=== 测试完成 ===")
    }
} 