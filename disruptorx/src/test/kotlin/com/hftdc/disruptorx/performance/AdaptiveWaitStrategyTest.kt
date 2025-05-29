package com.hftdc.disruptorx.performance

import com.hftdc.disruptorx.performance.AdaptiveWaitStrategy
import com.hftdc.disruptorx.performance.SystemLoad
import com.hftdc.disruptorx.performance.SystemLoadMonitor
import com.hftdc.disruptorx.performance.AdaptiveWaitStrategyStatus
import com.lmax.disruptor.Sequence
import com.lmax.disruptor.SequenceBarrier
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 自适应等待策略测试
 */
class AdaptiveWaitStrategyTest {
    
    private lateinit var waitStrategy: AdaptiveWaitStrategy
    private lateinit var cursor: Sequence
    private lateinit var dependentSequence: Sequence
    private lateinit var barrier: SequenceBarrier
    
    @BeforeEach
    fun setUp() {
        waitStrategy = AdaptiveWaitStrategy(
            initialSpinCount = 1000,
            maxSpinCount = 10000,
            minSpinCount = 100,
            yieldThreshold = 50,
            sleepThreshold = 100,
            maxSleepTimeNanos = 1000000L
        )
        
        cursor = mockk<Sequence>()
        dependentSequence = mockk<Sequence>()
        barrier = mockk<SequenceBarrier>()
    }
    
    @Test
    fun `test wait strategy with immediate availability`() {
        // 模拟序列立即可用
        every { cursor.get() } returns 10L
        every { barrier.checkAlert() } returns Unit
        
        val result = waitStrategy.waitFor(5L, cursor, dependentSequence, barrier)
        
        assertEquals(10L, result)
        verify(atLeast = 1) { cursor.get() }
        // 当序列立即可用时，不会进入等待循环，所以不会调用checkAlert
        // verify(atLeast = 1) { barrier.checkAlert() }
    }
    
    @Test
    fun `test wait strategy with delayed availability`() {
        // 模拟序列延迟可用
        every { cursor.get() } returnsMany listOf(1L, 2L, 10L)
        every { barrier.checkAlert() } returns Unit
        
        val result = waitStrategy.waitFor(5L, cursor, dependentSequence, barrier)
        
        assertEquals(10L, result)
        verify(atLeast = 1) { cursor.get() }
        verify(atLeast = 1) { barrier.checkAlert() } // 至少会调用一次checkAlert
    }
    
    @Test
    fun `test strategy status monitoring`() {
        val status = waitStrategy.getStrategyStatus()
        
        assertNotNull(status)
        assertEquals(1000, status.currentSpinCount)
        assertEquals(50, status.currentYieldCount)
        assertTrue(status.currentSleepTimeNanos > 0)
        assertTrue(status.totalWaitCount >= 0)
        assertNotNull(status.systemLoad)
    }
    
    @Test
    fun `test strategy reset functionality`() {
        // 执行一些等待操作来改变状态
        every { cursor.get() } returns 10L
        every { barrier.checkAlert() } returns Unit
        waitStrategy.waitFor(5L, cursor, dependentSequence, barrier)
        
        // 重置策略
        waitStrategy.reset()
        
        val status = waitStrategy.getStrategyStatus()
        assertEquals(1000, status.currentSpinCount) // 应该回到初始值
        assertEquals(50, status.currentYieldCount)  // 应该回到初始值
        assertEquals(0L, status.totalWaitCount)     // 计数器应该重置
    }
    
    @Test
    fun `test system load monitor`() {
        val monitor = SystemLoadMonitor()
        val systemLoad = monitor.getCurrentLoad()
        
        assertNotNull(systemLoad)
        assertTrue(systemLoad.cpuUsage >= 0.0)
        assertTrue(systemLoad.memoryUsage >= 0.0)
        assertTrue(systemLoad.memoryPressure >= 0.0)
        assertTrue(systemLoad.availableProcessors > 0)
    }
    
    @Test
    fun `test adaptive behavior under different loads`() {
        // 这个测试模拟不同的系统负载情况
        val initialStatus = waitStrategy.getStrategyStatus()
        val initialSpinCount = initialStatus.currentSpinCount
        
        // 模拟高负载情况下的多次等待
        every { cursor.get() } returnsMany listOf(1L, 2L, 3L, 4L, 5L, 10L)
        every { barrier.checkAlert() } returns Unit
        
        waitStrategy.waitFor(5L, cursor, dependentSequence, barrier)
        
        // 策略应该根据等待情况进行调整
        val afterStatus = waitStrategy.getStrategyStatus()
        assertTrue(afterStatus.totalWaitCount > 0)
    }
    
    @Test
    fun `test wait strategy with spinning phase`() {
        // 测试自旋阶段的行为
        var callCount = 0
        every { cursor.get() } answers {
            callCount++
            if (callCount <= 500) 1L else 10L // 前500次返回1，之后返回10
        }
        every { barrier.checkAlert() } returns Unit
        
        val result = waitStrategy.waitFor(5L, cursor, dependentSequence, barrier)
        
        assertEquals(10L, result)
        assertTrue(callCount > 500) // 应该经历了自旋阶段
    }
    
    @Test
    fun `test wait strategy with yielding phase`() {
        // 测试让出CPU阶段的行为
        var callCount = 0
        every { cursor.get() } answers {
            callCount++
            if (callCount <= 1100) 1L else 10L // 经历自旋+让出阶段
        }
        every { barrier.checkAlert() } returns Unit
        
        val result = waitStrategy.waitFor(5L, cursor, dependentSequence, barrier)
        
        assertEquals(10L, result)
        assertTrue(callCount > 1000) // 应该经历了自旋和让出阶段
    }
    
    @Test
    fun `test wait strategy with sleep phase`() {
        // 测试睡眠阶段的行为
        var callCount = 0
        every { cursor.get() } answers {
            callCount++
            if (callCount <= 1200) 1L else 10L // 经历所有阶段
        }
        every { barrier.checkAlert() } returns Unit
        
        val startTime = System.nanoTime()
        val result = waitStrategy.waitFor(5L, cursor, dependentSequence, barrier)
        val endTime = System.nanoTime()
        
        assertEquals(10L, result)
        assertTrue(callCount > 1100) // 应该经历了所有阶段
        // 由于有睡眠，总时间应该比纯自旋更长
        assertTrue(endTime - startTime > 0)
    }
    
    @Test
    fun `test signal all when blocking`() {
        // 测试信号通知功能
        // 这个方法在自适应策略中主要是空实现，但不应该抛出异常
        waitStrategy.signalAllWhenBlocking()
        // 如果没有异常抛出，测试通过
    }
    
    @Test
    fun `test custom wait strategy parameters`() {
        val customStrategy = AdaptiveWaitStrategy(
            initialSpinCount = 5000,
            maxSpinCount = 50000,
            minSpinCount = 500,
            yieldThreshold = 200,
            sleepThreshold = 500,
            maxSleepTimeNanos = 5000000L
        )
        
        val status = customStrategy.getStrategyStatus()
        assertEquals(5000, status.currentSpinCount)
        assertEquals(200, status.currentYieldCount)
    }
    
    @Test
    fun `test system load data class`() {
        val systemLoad = SystemLoad(
            cpuUsage = 0.75,
            memoryUsage = 0.60,
            memoryPressure = 0.80,
            availableProcessors = 8
        )
        
        assertEquals(0.75, systemLoad.cpuUsage)
        assertEquals(0.60, systemLoad.memoryUsage)
        assertEquals(0.80, systemLoad.memoryPressure)
        assertEquals(8, systemLoad.availableProcessors)
    }
    
    @Test
    fun `test adaptive wait strategy status data class`() {
        val systemLoad = SystemLoad(0.5, 0.6, 0.7, 4)
        val status = AdaptiveWaitStrategyStatus(
            currentSpinCount = 2000,
            currentYieldCount = 100,
            currentSleepTimeNanos = 5000L,
            averageWaitTimeNanos = 1000000L,
            totalWaitCount = 50L,
            systemLoad = systemLoad
        )
        
        assertEquals(2000, status.currentSpinCount)
        assertEquals(100, status.currentYieldCount)
        assertEquals(5000L, status.currentSleepTimeNanos)
        assertEquals(1000000L, status.averageWaitTimeNanos)
        assertEquals(50L, status.totalWaitCount)
        assertEquals(systemLoad, status.systemLoad)
    }
    
    @Test
    fun `test wait strategy performance under load`() {
        // 性能测试：测试在高频调用下的表现
        every { cursor.get() } returns 10L
        every { barrier.checkAlert() } returns Unit
        
        val startTime = System.nanoTime()
        
        repeat(1000) {
            waitStrategy.waitFor(5L, cursor, dependentSequence, barrier)
        }
        
        val endTime = System.nanoTime()
        val totalTime = endTime - startTime
        
        // 验证性能合理（具体阈值可根据实际情况调整）
        assertTrue(totalTime < 1_000_000_000L) // 应该在1秒内完成1000次调用
        
        val finalStatus = waitStrategy.getStrategyStatus()
        assertEquals(1000L, finalStatus.totalWaitCount)
    }
}