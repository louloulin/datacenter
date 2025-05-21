package com.hftdc.disruptorx.core

import com.hftdc.disruptorx.distributed.DistributedWaitStrategy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class WaitStrategyFactoryTest {

    @Test
    fun `should create BusySpinWaitStrategy`() {
        val strategy = WaitStrategyFactory.createBusySpinWaitStrategy()
        assertTrue(strategy is BusySpinWaitStrategy)
    }
    
    @Test
    fun `should create YieldingWaitStrategy`() {
        val strategy = WaitStrategyFactory.createYieldingWaitStrategy()
        assertTrue(strategy is YieldingWaitStrategy)
    }
    
    @Test
    fun `should create SleepingWaitStrategy`() {
        val strategy = WaitStrategyFactory.createSleepingWaitStrategy()
        assertTrue(strategy is SleepingWaitStrategy)
    }
    
    @Test
    fun `should create SleepingWaitStrategy with custom parameters`() {
        val strategy = WaitStrategyFactory.createSleepingWaitStrategy(spinTries = 50)
        assertTrue(strategy is SleepingWaitStrategy)
    }
    
    @Test
    fun `should create AdaptiveWaitStrategy`() {
        val strategy = WaitStrategyFactory.createAdaptiveWaitStrategy()
        assertTrue(strategy is AdaptiveWaitStrategy)
    }
    
    @Test
    fun `should create AdaptiveWaitStrategy with custom parameters`() {
        val strategy = WaitStrategyFactory.createAdaptiveWaitStrategy(
            yieldThreshold = 200,
            sleepThreshold = 2000,
            parkThreshold = 20000
        )
        assertTrue(strategy is AdaptiveWaitStrategy)
    }
    
    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    fun `BusySpinWaitStrategy should wait correctly with timeout`() {
        val strategy = WaitStrategyFactory.createBusySpinWaitStrategy()
        testStrategyTimeout(strategy)
    }
    
    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    fun `YieldingWaitStrategy should wait correctly with timeout`() {
        val strategy = WaitStrategyFactory.createYieldingWaitStrategy()
        testStrategyTimeout(strategy)
    }
    
    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    fun `SleepingWaitStrategy should wait correctly with timeout`() {
        val strategy = WaitStrategyFactory.createSleepingWaitStrategy()
        testStrategyTimeout(strategy)
    }
    
    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    fun `AdaptiveWaitStrategy should wait correctly with timeout`() {
        val strategy = WaitStrategyFactory.createAdaptiveWaitStrategy()
        testStrategyTimeout(strategy)
    }
    
    private fun testStrategyTimeout(strategy: DistributedWaitStrategy) {
        val startTime = System.nanoTime()
        val waitTimeNanos = 10_000_000L // 10 milliseconds
        
        // 调用带超时的等待方法
        strategy.waitForSequenceWithTimeout(0, waitTimeNanos)
        
        val endTime = System.nanoTime()
        val elapsedTimeNanos = endTime - startTime
        
        // 验证等待时间大约在设定的超时时间附近
        // 由于操作系统调度原因，可能会有一些偏差
        assertTrue(elapsedTimeNanos >= waitTimeNanos * 0.5, 
            "Waiting time too short: $elapsedTimeNanos ns")
        
        // 为了避免系统调度的不确定性，我们给予2倍的容忍度
        assertTrue(elapsedTimeNanos <= waitTimeNanos * 2.0, 
            "Waiting time too long: $elapsedTimeNanos ns")
    }
} 