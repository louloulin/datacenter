package com.hftdc.disruptorx.core

import com.lmax.disruptor.BlockingWaitStrategy
import com.lmax.disruptor.BusySpinWaitStrategy
import com.lmax.disruptor.SleepingWaitStrategy
import com.lmax.disruptor.WaitStrategy
import com.lmax.disruptor.YieldingWaitStrategy
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * 等待策略工厂，提供不同类型的等待策略实例
 */
object WaitStrategyFactory {
    
    /**
     * 创建阻塞等待策略
     * 特点：最低CPU使用率，但吞吐量较低
     * @return 阻塞等待策略
     */
    fun createBlockingWaitStrategy(): WaitStrategy {
        return BlockingWaitStrategy()
    }
    
    /**
     * 创建忙等待策略
     * 特点：最高性能，但CPU使用率极高
     * @return 忙等待策略
     */
    fun createBusySpinWaitStrategy(): WaitStrategy {
        return BusySpinWaitStrategy()
    }
    
    /**
     * 创建让步等待策略
     * 特点：平衡性能和CPU使用，适合多核系统
     * @return 让步等待策略
     */
    fun createYieldingWaitStrategy(): WaitStrategy {
        return YieldingWaitStrategy()
    }
    
    /**
     * 创建睡眠等待策略
     * 特点：低CPU使用率，逐渐增加响应延迟
     * @param retries 重试次数
     * @param sleepTime 睡眠时间
     * @param timeUnit 时间单位
     * @return 睡眠等待策略
     */
    fun createSleepingWaitStrategy(
        retries: Int = 200,
        sleepTime: Long = 100,
        timeUnit: TimeUnit = TimeUnit.NANOSECONDS
    ): WaitStrategy {
        return SleepingWaitStrategy(retries, sleepTime, timeUnit)
    }
    
    /**
     * 创建睡眠等待策略
     * 特点：低CPU使用率，逐渐增加响应延迟
     * @param retries 重试次数
     * @param sleepTime 睡眠时间（Kotlin Duration）
     * @return 睡眠等待策略
     */
    fun createSleepingWaitStrategy(
        retries: Int = 200,
        sleepTime: Duration
    ): WaitStrategy {
        val javaDuration = sleepTime.toJavaDuration()
        return SleepingWaitStrategy(retries, javaDuration.toNanos(), TimeUnit.NANOSECONDS)
    }
    
    /**
     * 自适应等待策略
     * 结合让步和睡眠策略的优点
     */
    class AdaptiveWaitStrategy(
        private val yieldThreshold: Int = 100,
        private val sleepThreshold: Int = 1000,
        private val parkThreshold: Int = 10000
    ) : WaitStrategy {
        private val yieldingStrategy = YieldingWaitStrategy()
        private val sleepingStrategy = SleepingWaitStrategy()
        
        override fun waitFor(sequence: Long, cursor: com.lmax.disruptor.Sequence, dependentSequence: com.lmax.disruptor.Sequence, barrier: com.lmax.disruptor.SequenceBarrier): Long {
            var counter = 0
            var availableSequence = dependentSequence.get()
            
            while (sequence > availableSequence) {
                counter++
                
                if (counter < yieldThreshold) {
                    // 低延迟模式 - 使用忙等待
                    Thread.onSpinWait()
                } else if (counter < sleepThreshold) {
                    // 中等延迟模式 - 使用让步
                    Thread.yield()
                } else if (counter < parkThreshold) {
                    // 高延迟模式 - 使用短暂睡眠
                    try {
                        TimeUnit.NANOSECONDS.sleep(1)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        barrier.alert()
                    }
                } else {
                    // 超长延迟模式 - 使用阻塞
                    try {
                        TimeUnit.MILLISECONDS.sleep(1)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        barrier.alert()
                    }
                }
                
                availableSequence = dependentSequence.get()
            }
            
            return availableSequence
        }
        
        override fun signalAllWhenBlocking() {
            // 自适应策略不需要特殊处理
        }
    }
    
    /**
     * 创建自适应等待策略
     * 特点：根据等待时间自动调整策略
     * @param yieldThreshold 让步阈值
     * @param sleepThreshold 睡眠阈值
     * @param parkThreshold 阻塞阈值
     * @return 自适应等待策略
     */
    fun createAdaptiveWaitStrategy(
        yieldThreshold: Int = 100,
        sleepThreshold: Int = 1000,
        parkThreshold: Int = 10000
    ): WaitStrategy {
        return AdaptiveWaitStrategy(yieldThreshold, sleepThreshold, parkThreshold)
    }
} 