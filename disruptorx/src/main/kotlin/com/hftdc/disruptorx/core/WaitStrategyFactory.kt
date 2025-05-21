package com.hftdc.disruptorx.core

import com.hftdc.disruptorx.distributed.DistributedWaitStrategy
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * 等待策略工厂
 * 提供各种等待策略实现，用于性能优化
 */
object WaitStrategyFactory {
    
    /**
     * 创建忙等待策略
     * 提供最低延迟，但CPU使用率最高
     * @return 忙等待策略
     */
    fun createBusySpinWaitStrategy(): DistributedWaitStrategy {
        return BusySpinWaitStrategy()
    }
    
    /**
     * 创建让出等待策略
     * 提供较低延迟，同时降低CPU使用率
     * @return 让出等待策略
     */
    fun createYieldingWaitStrategy(): DistributedWaitStrategy {
        return YieldingWaitStrategy()
    }
    
    /**
     * 创建休眠等待策略
     * 提供低CPU使用率，但延迟较高
     * @param spinTries 自旋尝试次数，默认100
     * @return 休眠等待策略
     */
    fun createSleepingWaitStrategy(spinTries: Int = 100): DistributedWaitStrategy {
        return SleepingWaitStrategy(spinTries)
    }
    
    /**
     * 创建自适应等待策略
     * 根据等待时间自动调整等待策略
     * @param yieldThreshold 让出阈值（纳秒）
     * @param sleepThreshold 休眠阈值（纳秒）
     * @param parkThreshold 阻塞阈值（纳秒）
     * @return 自适应等待策略
     */
    fun createAdaptiveWaitStrategy(
        yieldThreshold: Long = 100,
        sleepThreshold: Long = 1000,
        parkThreshold: Long = 10000
    ): DistributedWaitStrategy {
        return AdaptiveWaitStrategy(yieldThreshold, sleepThreshold, parkThreshold)
    }
}

/**
 * 忙等待策略
 * 提供最低延迟，但CPU使用率最高
 */
class BusySpinWaitStrategy : DistributedWaitStrategy {
    
    override fun waitForSequence(sequence: Long) {
        // 纯自旋等待，不释放CPU
    }
    
    override fun waitForSequenceWithTimeout(sequence: Long, timeoutNanos: Long) {
        val endTime = System.nanoTime() + timeoutNanos
        while (System.nanoTime() < endTime) {
            // 纯自旋等待，直到超时
        }
    }
}

/**
 * 让出等待策略
 * 提供较低延迟，同时降低CPU使用率
 */
class YieldingWaitStrategy : DistributedWaitStrategy {
    
    override fun waitForSequence(sequence: Long) {
        // 让出CPU时间片
        Thread.yield()
    }
    
    override fun waitForSequenceWithTimeout(sequence: Long, timeoutNanos: Long) {
        val endTime = System.nanoTime() + timeoutNanos
        while (System.nanoTime() < endTime) {
            Thread.yield()
        }
    }
}

/**
 * 休眠等待策略
 * 提供低CPU使用率，但延迟较高
 */
class SleepingWaitStrategy(private val spinTries: Int) : DistributedWaitStrategy {
    
    override fun waitForSequence(sequence: Long) {
        var counter = spinTries
        
        while (counter > 0) {
            counter--
            // 先尝试自旋一定次数
        }
        
        // 超过自旋次数后开始让出CPU
        Thread.yield()
        
        // 如果还是没有可用序列，开始短时间休眠
        try {
            TimeUnit.MILLISECONDS.sleep(1)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }
    
    override fun waitForSequenceWithTimeout(sequence: Long, timeoutNanos: Long) {
        val endTime = System.nanoTime() + timeoutNanos
        
        var counter = spinTries
        
        while (System.nanoTime() < endTime) {
            if (counter > 0) {
                counter--
                // 先尝试自旋一定次数
            } else {
                // 超过自旋次数后开始让出CPU
                Thread.yield()
                
                // 如果还有较长时间，开始短时间休眠
                if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1) < endTime) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw RuntimeException(e)
                    }
                }
            }
        }
    }
}

/**
 * 自适应等待策略
 * 根据等待时间自动调整等待策略
 */
class AdaptiveWaitStrategy(
    private val yieldThreshold: Long,
    private val sleepThreshold: Long,
    private val parkThreshold: Long
) : DistributedWaitStrategy {
    
    override fun waitForSequence(sequence: Long) {
        var waitTime = 0L
        
        // 根据等待时间选择不同策略
        if (waitTime < yieldThreshold) {
            // 短期等待：忙等待
        } else if (waitTime < sleepThreshold) {
            // 中等等待：让出CPU
            Thread.yield()
        } else if (waitTime < parkThreshold) {
            // 较长等待：短时间休眠
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(waitTime / 2, 1000000))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeException(e)
            }
        } else {
            // 长时间等待：阻塞线程
            LockSupport.parkNanos(waitTime / 4)
        }
        
        // 增加等待时间
        waitTime += 1000
    }
    
    override fun waitForSequenceWithTimeout(sequence: Long, timeoutNanos: Long) {
        val endTime = System.nanoTime() + timeoutNanos
        var waitTime = 0L
        
        while (System.nanoTime() < endTime) {
            // 根据等待时间选择不同策略
            if (waitTime < yieldThreshold) {
                // 短期等待：忙等待
            } else if (waitTime < sleepThreshold) {
                // 中等等待：让出CPU
                Thread.yield()
            } else if (waitTime < parkThreshold) {
                // 较长等待：短时间休眠
                val remainingNanos = endTime - System.nanoTime()
                if (remainingNanos > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos / 2, 1000000))
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw RuntimeException(e)
                    }
                }
            } else {
                // 长时间等待：阻塞线程
                val remainingNanos = endTime - System.nanoTime()
                if (remainingNanos > 0) {
                    LockSupport.parkNanos(remainingNanos / 4)
                }
            }
            
            // 增加等待时间
            waitTime += 1000
        }
    }
} 