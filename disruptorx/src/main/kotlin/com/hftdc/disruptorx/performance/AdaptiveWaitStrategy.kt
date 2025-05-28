package com.hftdc.disruptorx.performance

import com.lmax.disruptor.AlertException
import com.lmax.disruptor.Sequence
import com.lmax.disruptor.SequenceBarrier
import com.lmax.disruptor.WaitStrategy
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.min

/**
 * 自适应等待策略
 * 根据系统负载和延迟要求动态调整等待行为
 */
class AdaptiveWaitStrategy(
    private val initialSpinCount: Int = 10000,
    private val maxSpinCount: Int = 100000,
    private val minSpinCount: Int = 1000,
    private val yieldThreshold: Int = 100,
    private val sleepThreshold: Int = 1000,
    private val maxSleepTimeNanos: Long = 1000000L // 1ms
) : WaitStrategy {
    
    // 自适应参数
    @Volatile
    private var currentSpinCount = initialSpinCount
    @Volatile
    private var currentYieldCount = yieldThreshold
    @Volatile
    private var currentSleepTimeNanos = 1000L // 1μs
    
    // 性能统计
    private var lastWaitTime = System.nanoTime()
    private var waitCount = 0L
    private var totalWaitTime = 0L
    private var consecutiveTimeouts = 0
    
    // 系统负载监控
    private val systemLoadMonitor = SystemLoadMonitor()
    
    override fun waitFor(
        sequence: Long,
        cursor: Sequence,
        dependentSequence: Sequence,
        barrier: SequenceBarrier
    ): Long {
        val startTime = System.nanoTime()
        var availableSequence: Long
        var counter = 0
        
        // 第一阶段：自适应自旋等待
        while (cursor.get().also { availableSequence = it } < sequence) {
            counter++
            barrier.checkAlert()
            
            if (counter < currentSpinCount) {
                // 自旋等待
                continue
            } else if (counter < currentSpinCount + currentYieldCount) {
                // 让出CPU时间片
                Thread.yield()
            } else {
                // 短暂睡眠
                LockSupport.parkNanos(currentSleepTimeNanos)
                
                // 检查是否需要调整睡眠时间
                if (counter % sleepThreshold == 0) {
                    adjustSleepTime()
                }
            }
            
            // 定期检查系统负载并调整策略
            if (counter % 10000 == 0) {
                adaptToSystemLoad()
            }
        }
        
        // 更新性能统计
        updatePerformanceStats(startTime, counter)
        
        return availableSequence
    }
    
    override fun signalAllWhenBlocking() {
        // 唤醒所有等待的线程
        // 在自适应策略中，主要通过调整参数来优化性能
    }
    
    /**
     * 根据系统负载调整等待策略
     */
    private fun adaptToSystemLoad() {
        val systemLoad = systemLoadMonitor.getCurrentLoad()
        
        when {
            systemLoad.cpuUsage > 0.8 -> {
                // 高CPU负载：减少自旋，增加睡眠
                currentSpinCount = max(minSpinCount, (currentSpinCount * 0.8).toInt())
                currentSleepTimeNanos = min(maxSleepTimeNanos, (currentSleepTimeNanos * 1.5).toLong())
            }
            systemLoad.cpuUsage < 0.3 -> {
                // 低CPU负载：增加自旋，减少睡眠
                currentSpinCount = min(maxSpinCount, (currentSpinCount * 1.2).toInt())
                currentSleepTimeNanos = max(1000L, (currentSleepTimeNanos * 0.8).toLong())
            }
            systemLoad.memoryPressure > 0.9 -> {
                // 高内存压力：减少缓存，优化内存使用
                currentYieldCount = max(50, (currentYieldCount * 0.9).toInt())
            }
        }
    }
    
    /**
     * 调整睡眠时间
     */
    private fun adjustSleepTime() {
        val avgWaitTime = if (waitCount > 0) totalWaitTime / waitCount else 0L
        
        when {
            avgWaitTime > 10000000L -> { // 超过10ms
                // 等待时间过长，减少睡眠时间
                currentSleepTimeNanos = max(1000L, (currentSleepTimeNanos * 0.9).toLong())
                consecutiveTimeouts++
            }
            avgWaitTime < 1000000L -> { // 少于1ms
                // 等待时间较短，可以适当增加睡眠时间
                currentSleepTimeNanos = min(maxSleepTimeNanos, (currentSleepTimeNanos * 1.1).toLong())
                consecutiveTimeouts = 0
            }
            else -> {
                consecutiveTimeouts = 0
            }
        }
        
        // 如果连续超时，采用更激进的调整
        if (consecutiveTimeouts > 10) {
            currentSpinCount = max(minSpinCount, currentSpinCount / 2)
            currentSleepTimeNanos = max(1000L, currentSleepTimeNanos / 2)
            consecutiveTimeouts = 0
        }
    }
    
    /**
     * 更新性能统计
     */
    private fun updatePerformanceStats(startTime: Long, iterations: Int) {
        val waitTime = System.nanoTime() - startTime
        waitCount++
        totalWaitTime += waitTime
        lastWaitTime = waitTime
        
        // 定期重置统计信息，避免溢出
        if (waitCount > 1000000) {
            waitCount /= 2
            totalWaitTime /= 2
        }
    }
    
    /**
     * 获取当前策略状态
     */
    fun getStrategyStatus(): AdaptiveWaitStrategyStatus {
        return AdaptiveWaitStrategyStatus(
            currentSpinCount = currentSpinCount,
            currentYieldCount = currentYieldCount,
            currentSleepTimeNanos = currentSleepTimeNanos,
            averageWaitTimeNanos = if (waitCount > 0) totalWaitTime / waitCount else 0L,
            totalWaitCount = waitCount,
            systemLoad = systemLoadMonitor.getCurrentLoad()
        )
    }
    
    /**
     * 重置策略参数
     */
    fun reset() {
        currentSpinCount = initialSpinCount
        currentYieldCount = yieldThreshold
        currentSleepTimeNanos = 1000L
        waitCount = 0L
        totalWaitTime = 0L
        consecutiveTimeouts = 0
    }
}

/**
 * 系统负载监控器
 */
class SystemLoadMonitor {
    private val runtime = Runtime.getRuntime()
    private val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
    
    fun getCurrentLoad(): SystemLoad {
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()
        
        val memoryUsage = usedMemory.toDouble() / maxMemory.toDouble()
        val memoryPressure = if (maxMemory > 0) {
            (totalMemory.toDouble() / maxMemory.toDouble())
        } else 0.0
        
        // 获取CPU使用率（如果可用）
        val cpuUsage = try {
            if (osBean is com.sun.management.OperatingSystemMXBean) {
                osBean.processCpuLoad.takeIf { it >= 0.0 } ?: 0.5
            } else {
                0.5 // 默认值
            }
        } catch (e: Exception) {
            0.5 // 默认值
        }
        
        return SystemLoad(
            cpuUsage = cpuUsage,
            memoryUsage = memoryUsage,
            memoryPressure = memoryPressure,
            availableProcessors = runtime.availableProcessors()
        )
    }
}

/**
 * 系统负载信息
 */
data class SystemLoad(
    val cpuUsage: Double,        // CPU使用率 (0.0-1.0)
    val memoryUsage: Double,     // 内存使用率 (0.0-1.0)
    val memoryPressure: Double,  // 内存压力 (0.0-1.0)
    val availableProcessors: Int // 可用处理器数量
)

/**
 * 自适应等待策略状态
 */
data class AdaptiveWaitStrategyStatus(
    val currentSpinCount: Int,
    val currentYieldCount: Int,
    val currentSleepTimeNanos: Long,
    val averageWaitTimeNanos: Long,
    val totalWaitCount: Long,
    val systemLoad: SystemLoad
)