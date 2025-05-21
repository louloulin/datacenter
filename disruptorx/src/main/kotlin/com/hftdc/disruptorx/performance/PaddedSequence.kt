package com.hftdc.disruptorx.performance

import com.lmax.disruptor.Sequence
import java.util.concurrent.atomic.AtomicLong

/**
 * 缓存行填充的序列
 * 通过在序列周围添加填充避免CPU缓存的伪共享问题
 *
 * @property initialValue 序列初始值
 */
class PaddedSequence(initialValue: Long = -1) : Sequence(initialValue) {
    // 前填充 - 避免与前面的变量共享缓存行
    private val p1: Long = 7L
    private val p2: Long = 7L
    private val p3: Long = 7L
    private val p4: Long = 7L
    private val p5: Long = 7L
    private val p6: Long = 7L
    private val p7: Long = 7L
    
    // 原子序列值在Sequence基类中
    
    // 后填充 - 避免与后面的变量共享缓存行
    private val p8: Long = 7L
    private val p9: Long = 7L
    private val p10: Long = 7L
    private val p11: Long = 7L
    private val p12: Long = 7L
    private val p13: Long = 7L
    private val p14: Long = 7L
    
    /**
     * 防止优化器消除填充字段
     */
    fun preventOptimization(): Long {
        // 这个方法永远不会被调用，但会阻止JIT优化器消除填充字段
        return p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9 + p10 + p11 + p12 + p13 + p14
    }
}

/**
 * 带监控的填充序列
 * 除防止伪共享外，还可以记录更新频率等指标
 */
class MonitoredPaddedSequence(initialValue: Long = -1) {
    // 前填充
    private val p1: Long = 7L
    private val p2: Long = 7L
    private val p3: Long = 7L
    private val p4: Long = 7L
    private val p5: Long = 7L
    private val p6: Long = 7L
    private val p7: Long = 7L
    
    // 序列值及统计信息
    private val sequence = AtomicLong(initialValue)
    private val updateCount = AtomicLong(0)
    private var lastUpdateTime = System.nanoTime()
    
    // 后填充
    private val p8: Long = 7L
    private val p9: Long = 7L
    private val p10: Long = 7L
    private val p11: Long = 7L
    private val p12: Long = 7L
    private val p13: Long = 7L
    private val p14: Long = 7L
    
    /**
     * 获取当前序列值
     * @return 当前序列值
     */
    fun get(): Long {
        return sequence.get()
    }
    
    /**
     * 设置序列值
     * @param value 新序列值
     */
    fun set(value: Long) {
        sequence.set(value)
        updateCount.incrementAndGet()
        lastUpdateTime = System.nanoTime()
    }
    
    /**
     * 原子递增并获取
     * @return 递增后的值
     */
    fun incrementAndGet(): Long {
        val value = sequence.incrementAndGet()
        updateCount.incrementAndGet()
        lastUpdateTime = System.nanoTime()
        return value
    }
    
    /**
     * 原子增加指定值并获取
     * @param increment 增加的值
     * @return 增加后的值
     */
    fun addAndGet(increment: Long): Long {
        val value = sequence.addAndGet(increment)
        updateCount.incrementAndGet()
        lastUpdateTime = System.nanoTime()
        return value
    }
    
    /**
     * 获取更新次数
     * @return 更新次数
     */
    fun getUpdateCount(): Long {
        return updateCount.get()
    }
    
    /**
     * 获取最后更新时间（纳秒）
     * @return 最后更新时间戳
     */
    fun getLastUpdateTime(): Long {
        return lastUpdateTime
    }
    
    /**
     * 防止优化器消除填充字段
     */
    fun preventOptimization(): Long {
        return p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9 + p10 + p11 + p12 + p13 + p14
    }
} 