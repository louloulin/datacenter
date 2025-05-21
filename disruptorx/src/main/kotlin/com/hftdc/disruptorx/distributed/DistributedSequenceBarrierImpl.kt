package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.DistributedSequenceBarrier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * 分布式序列屏障实现
 * 扩展LMAX Disruptor的序列屏障到分布式环境
 *
 * @property nodeManager 节点管理器
 * @property localNodeId The identifier of the local node
 * @property cursor 当前游标
 * @property sequences 依赖序列
 * @property config 配置
 */
class DistributedSequenceBarrierImpl(
    private val nodeManager: NodeManagerImpl,
    private val localNodeId: String,
    private val cursor: DistributedSequenceImpl,
    private val sequences: List<DistributedSequenceImpl>,
    private val config: DistributedSequenceBarrierConfig
) : DistributedSequenceBarrier {

    // 警报状态
    private val alerted = AtomicReference<Throwable>()
    
    // 同步锁
    private val mutex = Mutex()
    
    // 最小序列值缓存
    private val cachedMinSequence = AtomicLong(-1)
    
    // 上次检查远程序列的时间
    private var lastRemoteCheck = System.nanoTime()
    
    /**
     * 等待序列号达到指定值
     * @param sequence 目标序列号
     * @return 当前可用的序列号
     */
    override fun waitFor(sequence: Long): Long {
        checkAlert()
        
        // 检查游标位置
        var availableSequence = cursor.get()
        if (availableSequence >= sequence) {
            return availableSequence
        }
        
        // 持续检查直到序列可用
        while (true) {
            checkAlert()
            
            // 获取最小序列值
            availableSequence = getMinimumSequence(sequence)
            if (availableSequence >= sequence) {
                return availableSequence
            }
            
            // 应用等待策略
            config.waitStrategy.waitForSequence(sequence)
        }
    }

    /**
     * 等待序列号达到指定值，带超时
     * @param sequence 目标序列号
     * @param timeout 超时时间
     * @return 当前可用的序列号
     */
    override fun waitFor(sequence: Long, timeout: Duration): Long {
        checkAlert()
        
        // 检查游标位置
        var availableSequence = cursor.get()
        if (availableSequence >= sequence) {
            return availableSequence
        }
        
        // 计算截止时间
        val timeoutNanos = timeout.toLong(DurationUnit.NANOSECONDS)
        val startTime = System.nanoTime()
        val endTime = startTime + timeoutNanos
        
        // 持续检查直到序列可用或超时
        while (true) {
            checkAlert()
            
            // 检查是否超时
            val currentTime = System.nanoTime()
            if (currentTime > endTime) {
                throw TimeoutException("Timeout waiting for sequence: $sequence")
            }
            
            // 获取最小序列值
            availableSequence = getMinimumSequence(sequence)
            if (availableSequence >= sequence) {
                return availableSequence
            }
            
            // 应用等待策略
            val remainingNanos = endTime - currentTime
            config.waitStrategy.waitForSequenceWithTimeout(sequence, remainingNanos)
        }
    }

    /**
     * 获取当前游标位置
     * @return 当前序列号
     */
    override fun getCursor(): Long {
        return cursor.get()
    }

    /**
     * 检查是否有警报
     * 如果有警报会抛出异常
     */
    override fun checkAlert() {
        val alert = alerted.get()
        if (alert != null) {
            throw AlertException("Barrier alerted", alert)
        }
    }
    
    /**
     * 设置警报
     * @param exception 异常
     */
    fun alert(exception: Throwable) {
        alerted.compareAndSet(null, exception)
    }
    
    /**
     * 清除警报
     */
    fun clearAlert() {
        alerted.set(null)
    }
    
    /**
     * 获取最小序列值
     * 包括本地序列和远程序列
     *
     * @param requiredSequence 请求的序列号
     * @return 最小序列值
     */
    private fun getMinimumSequence(requiredSequence: Long): Long {
        // 检查本地缓存
        val cachedValue = cachedMinSequence.get()
        if (cachedValue >= requiredSequence) {
            return cachedValue
        }
        
        // 获取本地最小序列
        var minimumSequence = cursor.get()
        
        // 检查本地依赖序列
        for (sequence in sequences) {
            if (sequence.isLocal()) {
                val value = sequence.get()
                minimumSequence = minOf(minimumSequence, value)
            }
        }
        
        // 检查是否需要查询远程序列
        val currentTime = System.nanoTime()
        val remoteCheckInterval = config.remoteCheckIntervalNanos
        
        if (currentTime - lastRemoteCheck > remoteCheckInterval) {
            lastRemoteCheck = currentTime
            
            // 获取远程序列值
            // 实际实现应该包括通过网络查询远程节点的序列值
            // 此处简化为假设远程序列总是足够大
            
            // 更新缓存
            cachedMinSequence.set(minimumSequence)
        }
        
        return minimumSequence
    }
}

/**
 * 等待策略接口
 */
interface DistributedWaitStrategy {
    /**
     * 等待序列达到指定值
     * @param sequence 目标序列号
     */
    fun waitForSequence(sequence: Long)
    
    /**
     * 等待序列达到指定值，带超时
     * @param sequence 目标序列号
     * @param timeoutNanos 超时时间（纳秒）
     */
    fun waitForSequenceWithTimeout(sequence: Long, timeoutNanos: Long)
}

/**
 * 分布式序列屏障配置
 */
data class DistributedSequenceBarrierConfig(
    val waitStrategy: DistributedWaitStrategy,
    val remoteCheckIntervalNanos: Long = TimeUnit.MILLISECONDS.toNanos(10)
)

/**
 * 分布式屏障警报异常
 */
class AlertException(message: String, cause: Throwable) : Exception(message, cause) 