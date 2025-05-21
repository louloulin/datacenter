package com.hftdc.disruptorx.core

import com.hftdc.disruptorx.api.EventHandler
import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.RingBuffer
import com.lmax.disruptor.YieldingWaitStrategy
import com.lmax.disruptor.dsl.ProducerType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EventProcessorWrapperTest {

    // 测试用事件类
    data class TestEvent(var value: Int = 0)
    
    // 测试用事件工厂
    private val eventFactory = EventFactory<TestEvent> { TestEvent() }
    
    // 测试资源
    private val bufferSize = 16
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var ringBuffer: RingBuffer<TestEvent>
    private lateinit var wrapper: RingBufferWrapper<TestEvent>
    
    @BeforeEach
    fun setup() {
        // 创建RingBuffer和包装器
        ringBuffer = RingBuffer.create(
            ProducerType.SINGLE,
            eventFactory,
            bufferSize,
            YieldingWaitStrategy()
        )
        wrapper = RingBufferWrapper(ringBuffer)
    }
    
    @AfterEach
    fun cleanup() {
        executor.shutdownNow()
    }
    
    @Test
    fun `should process events correctly`() {
        // 创建处理计数器
        val processedCount = AtomicInteger(0)
        val latch = CountDownLatch(3) // 预期处理3个事件
        
        // 创建事件处理器
        val eventHandler = object : EventHandler<TestEvent> {
            override fun onEvent(event: TestEvent, sequence: Long, endOfBatch: Boolean) {
                // 增加计数并减少锁存器
                processedCount.incrementAndGet()
                latch.countDown()
            }
        }
        
        // 创建序列屏障
        val sequenceBarrier = ringBuffer.newBarrier()
        
        // 创建处理器
        val processor = EventProcessorWrapper.createBatchProcessor(
            dataProvider = ringBuffer,
            sequenceBarrier = sequenceBarrier,
            eventHandler = eventHandler,
            executor = executor
        )
        
        // 启动处理器
        processor.start()
        assertTrue(processor.isRunning())
        
        // 发布3个事件
        for (i in 0 until 3) {
            ringBuffer.publishEvent { event, _ -> event.value = i }
        }
        
        // 等待处理完成
        assertTrue(latch.await(5, TimeUnit.SECONDS), "超时等待事件处理")
        
        // 验证处理器状态
        assertEquals(3, processedCount.get(), "应处理3个事件")
        
        // 停止处理器
        processor.stop()
        assertFalse(processor.isRunning())
    }
    
    @Test
    fun `should handle exceptions correctly`() {
        // 创建异常计数器
        val exceptionCount = AtomicInteger(0)
        
        // 创建事件处理器，故意抛出异常
        val eventHandler = object : EventHandler<TestEvent> {
            override fun onEvent(event: TestEvent, sequence: Long, endOfBatch: Boolean) {
                throw RuntimeException("测试异常")
            }
        }
        
        // 创建异常处理器
        val exceptionHandler = object : com.lmax.disruptor.ExceptionHandler<TestEvent> {
            override fun handleEventException(ex: Throwable, sequence: Long, event: TestEvent) {
                exceptionCount.incrementAndGet()
            }
            
            override fun handleOnStartException(ex: Throwable) {
                // 不需要处理
            }
            
            override fun handleOnShutdownException(ex: Throwable) {
                // 不需要处理
            }
        }
        
        // 创建序列屏障
        val sequenceBarrier = ringBuffer.newBarrier()
        
        // 创建处理器
        val processor = EventProcessorWrapper.createBatchProcessor(
            dataProvider = ringBuffer,
            sequenceBarrier = sequenceBarrier,
            eventHandler = eventHandler,
            exceptionHandler = exceptionHandler,
            executor = executor
        )
        
        // 启动处理器
        processor.start()
        
        // 发布1个事件
        ringBuffer.publishEvent { event, _ -> event.value = 42 }
        
        // 等待异常处理
        Thread.sleep(500)
        
        // 验证异常处理
        assertEquals(1, exceptionCount.get(), "应处理1个异常")
        
        // 停止处理器
        processor.stop()
    }
    
    @Test
    fun `should get sequence correctly`() {
        // 创建事件处理器
        val eventHandler = object : EventHandler<TestEvent> {
            override fun onEvent(event: TestEvent, sequence: Long, endOfBatch: Boolean) {
                // 空实现
            }
        }
        
        // 创建序列屏障
        val sequenceBarrier = ringBuffer.newBarrier()
        
        // 创建处理器
        val processor = EventProcessorWrapper.createBatchProcessor(
            dataProvider = ringBuffer,
            sequenceBarrier = sequenceBarrier,
            eventHandler = eventHandler,
            executor = executor
        )
        
        // 获取序列
        val sequence = processor.getSequence()
        
        // 验证序列初始值为-1
        assertEquals(-1L, sequence.get())
    }
} 