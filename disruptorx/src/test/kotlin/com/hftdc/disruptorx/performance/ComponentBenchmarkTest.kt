package com.hftdc.disruptorx.performance

import com.lmax.disruptor.BlockingWaitStrategy
import com.lmax.disruptor.BusySpinWaitStrategy
import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.RingBuffer
import com.lmax.disruptor.WaitStrategy
import com.lmax.disruptor.YieldingWaitStrategy
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
import com.lmax.disruptor.Sequence
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureNanoTime

/**
 * 组件级别基准测试
 * 分别测试每个性能优化组件的性能贡献
 */
@Tag("benchmark")
class ComponentBenchmarkTest {
    
    // 基准测试配置
    private val ringBufferSize = 1024 * 16 // 16K
    private val eventCount = 5_000_000 // 500万事件
    private val warmupCount = 500_000 // 50万预热
    private val producerCount = 2
    private val consumerCount = 2
    
    // 延迟直方图 (纳秒精度)
    private lateinit var standardHistogram: Histogram
    private lateinit var enhancedHistogram: Histogram
    
    // 基准测试事件
    data class TestEvent(
        var timestamp: Long = 0,
        var value: Long = 0,
        var data: ByteArray = ByteArray(64) // 固定大小数据
    ) {
        // 重写equals和hashCode，因为data是数组
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as TestEvent
            
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
    
    // 事件工厂
    class TestEventFactory : EventFactory<TestEvent> {
        override fun newInstance(): TestEvent = TestEvent()
    }
    
    @BeforeEach
    fun setUp() {
        // 创建延迟直方图，精度为3位有效数字，范围从1ns到5s
        standardHistogram = Histogram(TimeUnit.SECONDS.toNanos(5), 3)
        enhancedHistogram = Histogram(TimeUnit.SECONDS.toNanos(5), 3)
    }
    
    @AfterEach
    fun tearDown() {
        // 重置直方图
        standardHistogram.reset()
        enhancedHistogram.reset()
    }
    
    /**
     * 输出性能结果
     */
    private fun printResults(title: String, standard: Histogram, enhanced: Histogram, throughputStd: Int, throughputEnh: Int) {
        println("=== $title 性能比较 ===")
        println("标准版本:")
        println("  中位数延迟: ${standard.getValueAtPercentile(50.0) / 1000.0} 微秒")
        println("  99%延迟: ${standard.getValueAtPercentile(99.0) / 1000.0} 微秒")
        println("  99.9%延迟: ${standard.getValueAtPercentile(99.9) / 1000.0} 微秒")
        println("  最大延迟: ${standard.maxValue / 1000.0} 微秒")
        println("  吞吐量: $throughputStd 事件/秒")
        
        println("优化版本:")
        println("  中位数延迟: ${enhanced.getValueAtPercentile(50.0) / 1000.0} 微秒")
        println("  99%延迟: ${enhanced.getValueAtPercentile(99.0) / 1000.0} 微秒")
        println("  99.9%延迟: ${enhanced.getValueAtPercentile(99.9) / 1000.0} 微秒")
        println("  最大延迟: ${enhanced.maxValue / 1000.0} 微秒")
        println("  吞吐量: $throughputEnh 事件/秒")
        
        val throughputImprovement = (throughputEnh - throughputStd) * 100.0 / throughputStd
        val latencyImprovement = (standard.getValueAtPercentile(99.0) - enhanced.getValueAtPercentile(99.0)) * 100.0 / standard.getValueAtPercentile(99.0)
        
        println("性能提升:")
        println("  吞吐量提升: ${throughputImprovement.toInt()}%")
        println("  99%延迟改进: ${latencyImprovement.toInt()}%")
        println()
    }
    
    /**
     * 测试对象池的性能贡献
     */
    @Test
    fun `test object pool performance`() {
        // 无对象池版本
        val (throughputStd, _) = runStandardDisruptor(
            waitStrategy = YieldingWaitStrategy(),
            useThreadAffinity = false,
            usePaddedSequence = false,
            useOffHeapBuffer = false
        )
        
        // 使用对象池版本
        val (throughputEnh, _) = runEnhancedDisruptor(
            waitStrategy = YieldingWaitStrategy(),
            useThreadAffinity = false,
            usePaddedSequence = false,
            useOffHeapBuffer = false,
            useObjectPool = true
        )
        
        printResults("对象池", standardHistogram, enhancedHistogram, throughputStd, throughputEnh)
    }
    
    /**
     * 测试线程亲和性的性能贡献
     */
    @Test
    fun `test thread affinity performance`() {
        // 无线程亲和性版本
        val (throughputStd, _) = runStandardDisruptor(
            waitStrategy = YieldingWaitStrategy(),
            useThreadAffinity = false,
            usePaddedSequence = false,
            useOffHeapBuffer = false
        )
        
        // 使用线程亲和性版本
        val (throughputEnh, _) = runEnhancedDisruptor(
            waitStrategy = YieldingWaitStrategy(),
            useThreadAffinity = true,
            usePaddedSequence = false,
            useOffHeapBuffer = false,
            useObjectPool = false
        )
        
        printResults("线程亲和性", standardHistogram, enhancedHistogram, throughputStd, throughputEnh)
    }
    
    /**
     * 测试缓存行填充的性能贡献
     */
    @Test
    fun `test padded sequence performance`() {
        // 无缓存行填充版本
        val (throughputStd, _) = runStandardDisruptor(
            waitStrategy = YieldingWaitStrategy(),
            useThreadAffinity = false,
            usePaddedSequence = false,
            useOffHeapBuffer = false
        )
        
        // 使用缓存行填充版本
        val (throughputEnh, _) = runEnhancedDisruptor(
            waitStrategy = YieldingWaitStrategy(),
            useThreadAffinity = false,
            usePaddedSequence = true,
            useOffHeapBuffer = false,
            useObjectPool = false
        )
        
        printResults("缓存行填充", standardHistogram, enhancedHistogram, throughputStd, throughputEnh)
    }
    
    /**
     * 测试堆外内存的性能贡献
     */
    @Test
    fun `test off heap buffer performance`() {
        // 无堆外内存版本
        val (throughputStd, _) = runStandardDisruptor(
            waitStrategy = YieldingWaitStrategy(),
            useThreadAffinity = false,
            usePaddedSequence = false,
            useOffHeapBuffer = false
        )
        
        // 使用堆外内存版本
        val (throughputEnh, _) = runEnhancedDisruptor(
            waitStrategy = YieldingWaitStrategy(),
            useThreadAffinity = false,
            usePaddedSequence = false,
            useOffHeapBuffer = true,
            useObjectPool = false
        )
        
        printResults("堆外内存", standardHistogram, enhancedHistogram, throughputStd, throughputEnh)
    }
    
    /**
     * 测试所有性能优化的综合贡献
     */
    @Test
    fun `test all optimizations combined`() {
        // 无优化版本
        val (throughputStd, _) = runStandardDisruptor(
            waitStrategy = YieldingWaitStrategy(),
            useThreadAffinity = false,
            usePaddedSequence = false,
            useOffHeapBuffer = false
        )
        
        // 全部优化版本
        val (throughputEnh, _) = runEnhancedDisruptor(
            waitStrategy = YieldingWaitStrategy(),
            useThreadAffinity = true,
            usePaddedSequence = true,
            useOffHeapBuffer = true,
            useObjectPool = true
        )
        
        printResults("全部优化", standardHistogram, enhancedHistogram, throughputStd, throughputEnh)
    }
    
    /**
     * 测试不同等待策略的性能
     */
    @Test
    fun `test wait strategy performance`() {
        // 阻塞等待策略
        val (throughputBlock, _) = runStandardDisruptor(
            waitStrategy = BlockingWaitStrategy(),
            useThreadAffinity = false,
            usePaddedSequence = false,
            useOffHeapBuffer = false
        )
        
        // 重置直方图
        standardHistogram.reset()
        
        // 自旋等待策略
        val (throughputBusySpin, _) = runStandardDisruptor(
            waitStrategy = BusySpinWaitStrategy(),
            useThreadAffinity = false,
            usePaddedSequence = false,
            useOffHeapBuffer = false
        )
        
        println("=== 等待策略性能比较 ===")
        println("BlockingWaitStrategy:")
        println("  中位数延迟: ${standardHistogram.getValueAtPercentile(50.0) / 1000.0} 微秒")
        println("  99%延迟: ${standardHistogram.getValueAtPercentile(99.0) / 1000.0} 微秒")
        println("  吞吐量: $throughputBlock 事件/秒")
        
        println("BusySpinWaitStrategy:")
        println("  中位数延迟: ${enhancedHistogram.getValueAtPercentile(50.0) / 1000.0} 微秒")
        println("  99%延迟: ${enhancedHistogram.getValueAtPercentile(99.0) / 1000.0} 微秒")
        println("  吞吐量: $throughputBusySpin 事件/秒")
        
        val throughputImprovement = (throughputBusySpin - throughputBlock) * 100.0 / throughputBlock
        println("吞吐量提升: ${throughputImprovement.toInt()}%")
        println()
    }
    
    /**
     * 运行标准Disruptor
     */
    private fun runStandardDisruptor(
        waitStrategy: WaitStrategy,
        useThreadAffinity: Boolean,
        usePaddedSequence: Boolean,
        useOffHeapBuffer: Boolean
    ): Pair<Int, Long> {
        // 创建线程工厂
        val threadFactory = if (useThreadAffinity) {
            val cpuCores = ThreadAffinity.allocateCoresForCriticalProcessing(consumerCount + producerCount)
            ThreadAffinity.newThreadFactory("standard-thread", cpuCores)
        } else {
            ThreadFactory { r -> Thread(r, "standard-thread") }
        }
        
        // 创建Disruptor
        val disruptor = Disruptor(
            TestEventFactory(),
            ringBufferSize,
            threadFactory,
            ProducerType.MULTI,
            waitStrategy
        )
        
        // 监控序列
        val completedCount = AtomicLong(0)
        val completedSequence = if (usePaddedSequence) PaddedSequence(0) else Sequence(-1)
        
        // 可选的堆外内存
        val offHeapBuffer = if (useOffHeapBuffer) {
            OffHeapBuffer.allocate(1024 * 1024) // 1MB
        } else null
        
        // 设置事件处理器
        val handler = EventHandler<TestEvent> { event, sequence, _ ->
            try {
                // 记录延迟
                val latency = System.nanoTime() - event.timestamp
                standardHistogram.recordValue(latency)
                
                // 如果有堆外内存，使用它处理数据
                if (useOffHeapBuffer && offHeapBuffer != null && event.data.isNotEmpty()) {
                    offHeapBuffer.write(event.data)
                } else {
                    // 模拟处理 - 简单计算数据校验和
                    var sum: Byte = 0
                    for (b in event.data) {
                        sum = (sum + b).toByte()
                    }
                }
                
                // 更新计数和序列
                completedCount.incrementAndGet()
                if (usePaddedSequence) {
                    completedSequence.set(completedCount.get())
                }
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
            runStandardProducers(ringBuffer, warmupCount)
            // 等待预热完成
            while (completedCount.get() < warmupCount) {
                Thread.sleep(50)
            }
            
            // 重置计数
            completedCount.set(0)
            standardHistogram.reset()
            if (offHeapBuffer != null) {
                offHeapBuffer.reset()
            }
            
            // 运行实际测试
            val startTime = System.nanoTime()
            runStandardProducers(ringBuffer, eventCount)
            
            // 等待所有事件处理完成
            while (completedCount.get() < eventCount) {
                Thread.sleep(50)
            }
            
            val endTime = System.nanoTime()
            val durationSeconds = (endTime - startTime) / 1_000_000_000.0
            
            val throughput = (eventCount / durationSeconds).toInt()
            return Pair(throughput, endTime - startTime)
            
        } finally {
            disruptor.shutdown()
            if (offHeapBuffer != null) {
                offHeapBuffer.close()
            }
        }
    }
    
    /**
     * 运行增强版Disruptor
     */
    private fun runEnhancedDisruptor(
        waitStrategy: WaitStrategy,
        useThreadAffinity: Boolean,
        usePaddedSequence: Boolean,
        useOffHeapBuffer: Boolean,
        useObjectPool: Boolean
    ): Pair<Int, Long> {
        // 创建线程工厂
        val threadFactory = if (useThreadAffinity) {
            val cpuCores = ThreadAffinity.allocateCoresForCriticalProcessing(consumerCount + producerCount)
            ThreadAffinity.newThreadFactory("enhanced-thread", cpuCores)
        } else {
            ThreadFactory { r -> Thread(r, "enhanced-thread") }
        }
        
        // 可选对象池
        val eventPool = if (useObjectPool) {
            EventObjectPool<TestEvent>(
                ringBufferSize,
                ringBufferSize * 2,
                { TestEvent() }
            )
        } else null
        
        // 创建Disruptor
        val disruptor = Disruptor(
            TestEventFactory(),
            ringBufferSize,
            threadFactory,
            ProducerType.MULTI,
            waitStrategy
        )
        
        // 监控序列
        val completedCount = AtomicLong(0)
        val completedSequence = if (usePaddedSequence) PaddedSequence(0) else Sequence(-1)
        
        // 可选的堆外内存
        val offHeapBuffer = if (useOffHeapBuffer) {
            OffHeapBuffer.allocate(1024 * 1024) // 1MB
        } else null
        
        // 设置事件处理器
        val handler = EventHandler<TestEvent> { event, sequence, _ ->
            try {
                // 记录延迟
                val latency = System.nanoTime() - event.timestamp
                enhancedHistogram.recordValue(latency)
                
                // 如果有堆外内存，使用它处理数据
                if (useOffHeapBuffer && offHeapBuffer != null && event.data.isNotEmpty()) {
                    offHeapBuffer.write(event.data)
                } else {
                    // 模拟处理 - 简单计算数据校验和
                    var sum: Byte = 0
                    for (b in event.data) {
                        sum = (sum + b).toByte()
                    }
                }
                
                // 更新计数和序列
                completedCount.incrementAndGet()
                if (usePaddedSequence) {
                    completedSequence.set(completedCount.get())
                }
                
                // 如果使用对象池，回收事件对象
                if (useObjectPool && eventPool != null) {
                    eventPool.release(event)
                }
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
            runEnhancedProducers(ringBuffer, warmupCount, eventPool)
            // 等待预热完成
            while (completedCount.get() < warmupCount) {
                Thread.sleep(50)
            }
            
            // 重置计数
            completedCount.set(0)
            enhancedHistogram.reset()
            if (offHeapBuffer != null) {
                offHeapBuffer.reset()
            }
            
            // 运行实际测试
            val startTime = System.nanoTime()
            runEnhancedProducers(ringBuffer, eventCount, eventPool)
            
            // 等待所有事件处理完成
            while (completedCount.get() < eventCount) {
                Thread.sleep(50)
            }
            
            val endTime = System.nanoTime()
            val durationSeconds = (endTime - startTime) / 1_000_000_000.0
            
            val throughput = (eventCount / durationSeconds).toInt()
            return Pair(throughput, endTime - startTime)
            
        } finally {
            disruptor.shutdown()
            if (offHeapBuffer != null) {
                offHeapBuffer.close()
            }
        }
    }
    
    /**
     * 标准生产者运行方法
     */
    private fun runStandardProducers(ringBuffer: RingBuffer<TestEvent>, eventCount: Int) {
        val eventsPerProducer = eventCount / producerCount
        val barrier = CyclicBarrier(producerCount)
        val running = AtomicBoolean(true)
        
        // 创建生产者线程池
        val executor = Executors.newFixedThreadPool(producerCount)
        
        for (i in 0 until producerCount) {
            executor.submit {
                val startSequence = i * eventsPerProducer
                val endSequence = startSequence + eventsPerProducer
                
                try {
                    // 随机数据生成
                    val random = java.util.Random()
                    val data = ByteArray(64)
                    
                    // 等待所有生产者准备好
                    barrier.await()
                    
                    for (j in startSequence until endSequence) {
                        if (!running.get()) break
                        
                        random.nextBytes(data)
                        
                        ringBuffer.publishEvent { event, _ ->
                            event.timestamp = System.nanoTime()
                            event.value = j.toLong()
                            event.data = data.copyOf()
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
    
    /**
     * 增强版生产者运行方法
     */
    private fun runEnhancedProducers(
        ringBuffer: RingBuffer<TestEvent>,
        eventCount: Int,
        eventPool: EventObjectPool<TestEvent>?
    ) {
        val eventsPerProducer = eventCount / producerCount
        val barrier = CyclicBarrier(producerCount)
        val running = AtomicBoolean(true)
        
        // 创建生产者线程池
        val executor = Executors.newFixedThreadPool(producerCount)
        
        for (i in 0 until producerCount) {
            executor.submit {
                val startSequence = i * eventsPerProducer
                val endSequence = startSequence + eventsPerProducer
                
                try {
                    // 随机数据生成
                    val random = java.util.Random()
                    val data = ByteArray(64)
                    
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

    private fun getIterator(): Iterator<TestEvent> {
        val list = ArrayList<TestEvent>()
        list.add(TestEvent())
        return list.iterator()
    }

    private fun handleStringBuilder(sb: StringBuilder, index: Int, value: Char) {
        if (index >= 0 && index < sb.length) {
            sb.setCharAt(index, value)
        }
    }
} 