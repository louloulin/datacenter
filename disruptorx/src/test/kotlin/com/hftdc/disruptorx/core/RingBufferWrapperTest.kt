package com.hftdc.disruptorx.core

import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.YieldingWaitStrategy
import com.lmax.disruptor.dsl.ProducerType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RingBufferWrapperTest {

    // 测试用事件类
    data class TestEvent(var value: Int = 0)
    
    // 测试用事件工厂
    private val eventFactory = EventFactory<TestEvent> { TestEvent() }
    
    @Test
    fun `should create ring buffer with specified size`() {
        val bufferSize = 16 // 2的幂
        val wrapper = RingBufferWrapper.create(
            factory = eventFactory,
            bufferSize = bufferSize,
            waitStrategy = YieldingWaitStrategy()
        )
        
        // 验证缓冲区大小
        assertEquals(bufferSize, wrapper.getBufferSize())
    }
    
    @Test
    fun `should publish events with direct API`() {
        val bufferSize = 16
        val ringBuffer = RingBufferWrapper.create(
            factory = eventFactory,
            bufferSize = bufferSize,
            waitStrategy = YieldingWaitStrategy()
        ).unwrap()
        
        // 直接使用底层RingBuffer API发布
        val testValue = 42
        val seq = ringBuffer.next()
        try {
            val event = ringBuffer.get(seq)
            event.value = testValue
        } finally {
            ringBuffer.publish(seq)
        }
        
        // 验证事件数据
        val event = ringBuffer.get(seq)
        assertEquals(testValue, event.value)
    }
    
    @Test
    fun `tryPublish should return true when successful`() {
        val bufferSize = 4
        val wrapper = RingBufferWrapper.create(
            factory = eventFactory,
            bufferSize = bufferSize,
            waitStrategy = YieldingWaitStrategy()
        )
        
        // 尝试发布事件
        val result = wrapper.tryPublish { _, event ->
            event.value = 42
        }
        
        // 验证结果
        assertTrue(result, "tryPublish应返回true表示成功")
    }
    
    @Test
    fun `should create barrier correctly`() {
        val wrapper = RingBufferWrapper.create(
            factory = eventFactory,
            bufferSize = 16,
            waitStrategy = YieldingWaitStrategy()
        )
        
        // 创建屏障
        val barrier = wrapper.newBarrier()
        
        // 验证屏障
        assertNotNull(barrier)
    }
} 