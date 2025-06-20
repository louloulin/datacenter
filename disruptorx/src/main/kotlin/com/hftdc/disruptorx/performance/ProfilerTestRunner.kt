package com.hftdc.disruptorx.performance

import com.lmax.disruptor.BlockingWaitStrategy
import com.lmax.disruptor.BusySpinWaitStrategy
import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.RingBuffer
import com.lmax.disruptor.YieldingWaitStrategy
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 性能分析器测试程序
 * 运行不同配置的基准测试并收集详细性能数据
 */
class ProfilerTestRunner {
    
    // 测试配置
    private val ringBufferSize = 1024 * 32
    private val eventCount = 5_000_000
    private val warmupCount = 500_000
    private val producerCount = 4
    private val consumerCount = 4
    
    // 测试事件
    data class ProfiledEvent(
        var timestamp: Long = 0,
        var value: Long = 0,
        var data: ByteArray = ByteArray(64) // 固定大小数据
    ) {
        // 重写equals和hashCode，因为data是数组
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as ProfiledEvent
            
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
    
    // 测试事件工厂
    class ProfiledEventFactory : EventFactory<ProfiledEvent> {
        override fun newInstance(): ProfiledEvent = ProfiledEvent()
    }
    
    /**
     * 启动测试程序
     */
    fun start() {
        println("启动ProfilerTestRunner...")
        println("事件数: $eventCount, 预热: $warmupCount")
        println("生产者: $producerCount, 消费者: $consumerCount")
        println("RingBuffer大小: $ringBufferSize")
        
        // 输出目录可通过系统属性配置
        val outputDir = System.getProperty("performance.output.dir", "performance-results")
        
        // 运行不同等待策略的测试
        runTest("阻塞等待策略测试", BlockingWaitStrategy(), outputDir)
        runTest("自旋等待策略测试", BusySpinWaitStrategy(), outputDir)
        runTest("让步等待策略测试", YieldingWaitStrategy(), outputDir)
        
        // 运行组合优化测试
        runOptimizedTest("优化组合测试", YieldingWaitStrategy(), outputDir)
        
        println("所有测试完成。")
    }
    
    /**
     * 运行基准测试
     */
    private fun runTest(
        testName: String,
        waitStrategy: com.lmax.disruptor.WaitStrategy,
        outputDir: String
    ) {
        println("\n开始 $testName...")
        
        // 创建性能分析器
        val profiler = PerformanceProfiler(testName, outputDir)
        
        // 创建GC监控
        val gcMonitor = GcMonitor(profiler)
        gcMonitor.start()
        
        // 创建标准线程工厂
        val threadFactory = ThreadFactory { r -> Thread(r, "profiler-thread") }
        
        // 创建Disruptor
        val disruptor = Disruptor(
            ProfiledEventFactory(),
            ringBufferSize,
            threadFactory,
            ProducerType.MULTI,
            waitStrategy
        )
        
        // 计数器
        val completedCount = AtomicLong(0)
        
        // 设置事件处理器
        val handler = EventHandler<ProfiledEvent> { event, _, _ ->
            // 记录延迟
            profiler.recordLatency(event.timestamp)
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
            println("预热中...")
            runProducers(ringBuffer, warmupCount)
            
            // 等待预热完成
            while (completedCount.get() < warmupCount) {
                Thread.sleep(100)
            }
            
            // 重置计数
            completedCount.set(0)
            
            // 开始分析
            println("开始性能分析...")
            profiler.start()
            
            // 运行实际测试
            runProducers(ringBuffer, eventCount)
            
            // 等待所有事件处理完成
            while (completedCount.get() < eventCount) {
                Thread.sleep(100)
            }
            
            // 停止分析并生成报告
            val report = profiler.stopAndGenerateReport()
            
            println("$testName 完成: ${report.throughput} 事件/秒, 中位数延迟: ${report.medianLatencyMicros} 微秒")
            
        } finally {
            disruptor.shutdown()
        }
    }
    
    /**
     * 运行优化后的测试
     */
    private fun runOptimizedTest(
        testName: String,
        waitStrategy: com.lmax.disruptor.WaitStrategy,
        outputDir: String
    ) {
        println("\n开始 $testName...")
        
        // 创建性能分析器
        val profiler = PerformanceProfiler(testName, outputDir)
        
        // 创建GC监控
        val gcMonitor = GcMonitor(profiler)
        gcMonitor.start()
        
        // 1. 使用线程亲和性
        val cpuCores = ThreadAffinity.allocateCoresForCriticalProcessing(consumerCount + producerCount)
        val threadFactory = ThreadAffinity.newThreadFactory("optimized-thread", cpuCores)
        
        // 2. 使用对象池
        val eventPool = EventObjectPool<ProfiledEvent>(
            ringBufferSize,
            ringBufferSize * 2,
            { ProfiledEvent() }
        )
        
        // 3. 创建堆外内存
        val offHeapBuffer = OffHeapBuffer.allocate(1024 * 1024)
        
        // 4. 使用内存预分配器
        val memoryPreallocator = MemoryPreallocator<ByteArray>(64 * eventCount, 128, 1.5) { ByteArray(64) }
        
        // 5. 使用缓存行填充的序列
        val completedSequence = PaddedSequence(0)
        val completedCount = AtomicLong(0)
        
        // 创建Disruptor
        val disruptor = Disruptor(
            ProfiledEventFactory(),
            ringBufferSize,
            threadFactory,
            ProducerType.MULTI,
            waitStrategy
        )
        
        // 设置事件处理器
        val handler = EventHandler<ProfiledEvent> { event, _, _ ->
            try {
                // 记录延迟
                profiler.recordLatency(event.timestamp)
                
                // 使用堆外内存处理数据
                if (event.data.isNotEmpty()) {
                    offHeapBuffer.write(event.data)
                    
                    // 使用预分配内存
                    val buffer = memoryPreallocator.allocate()
                    System.arraycopy(event.data, 0, buffer, 0, Math.min(event.data.size, buffer.size))
                    memoryPreallocator.recycle(buffer)
                }
                
                // 更新计数和序列
                completedCount.incrementAndGet()
                completedSequence.set(completedCount.get())
                
                // 将事件对象返回到对象池
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
            println("预热中...")
            runOptimizedProducers(ringBuffer, warmupCount, eventPool)
            
            // 等待预热完成
            while (completedCount.get() < warmupCount) {
                Thread.sleep(100)
            }
            
            // 重置计数
            completedCount.set(0)
            completedSequence.set(0)
            
            // 开始分析
            println("开始性能分析...")
            profiler.start()
            
            // 运行实际测试
            runOptimizedProducers(ringBuffer, eventCount, eventPool)
            
            // 等待所有事件处理完成
            while (completedCount.get() < eventCount) {
                Thread.sleep(100)
            }
            
            // 停止分析并生成报告
            val report = profiler.stopAndGenerateReport()
            
            // 输出对象池统计
            println("对象池统计: 创建 ${eventPool.getCreatedCount()}, 重用 ${eventPool.getReusedCount()}")
            
            println("$testName 完成: ${report.throughput} 事件/秒, 中位数延迟: ${report.medianLatencyMicros} 微秒")
            
        } finally {
            disruptor.shutdown()
            offHeapBuffer.close()
            memoryPreallocator.clear() // 使用clear替代close
        }
    }
    
    /**
     * 启动标准生产者
     */
    private fun runProducers(ringBuffer: RingBuffer<ProfiledEvent>, eventCount: Int) {
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
     * 启动优化生产者
     */
    private fun runOptimizedProducers(
        ringBuffer: RingBuffer<ProfiledEvent>,
        eventCount: Int,
        eventPool: EventObjectPool<ProfiledEvent>
    ) {
        val eventsPerProducer = eventCount / producerCount
        val barrier = CyclicBarrier(producerCount)
        val running = AtomicBoolean(true)
        
        // 创建生产者线程池 - 使用线程亲和性
        val cpuCores = ThreadAffinity.allocateCoresForCriticalProcessing(producerCount)
        val executor = ThreadAffinity.newFixedThreadPool(producerCount, "producer", cpuCores)
        
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
                            // 从对象池获取事件对象
                            val pooledEvent = eventPool.acquire()
                            
                            pooledEvent.timestamp = System.nanoTime()
                            pooledEvent.value = j.toLong()
                            pooledEvent.data = data.copyOf()
                            
                            // 更新event引用
                            event.timestamp = pooledEvent.timestamp
                            event.value = pooledEvent.value
                            event.data = pooledEvent.data
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
    
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val runner = ProfilerTestRunner()
            runner.start()
        }
    }
} 