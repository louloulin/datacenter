package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.NodeInfo
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 故障类型枚举
 */
enum class FailureType {
    HEALTHY,           // 健康状态
    SLOW_RESPONSE,     // 响应缓慢
    HIGH_LOAD,         // 高负载
    NETWORK_FAILURE,   // 网络故障
    SUSPECTED,         // 疑似故障
    CONFIRMED_FAILURE  // 确认故障
}

/**
 * 心跳响应
 */
data class HeartbeatResponse(
    val nodeId: String,
    val timestamp: Long,
    val load: Double,
    val latency: Duration,
    val status: String = "OK"
)

/**
 * 快速故障检测器
 * 基于Phi Accrual故障检测算法，提供：
 * 1. 自适应故障检测阈值
 * 2. 网络抖动容忍
 * 3. 分层故障检测
 * 4. 快速故障恢复检测
 */
class FailureDetector(
    private val heartbeatInterval: Duration = 50.milliseconds,
    private val failureThreshold: Int = 3,
    private val maxSampleSize: Int = 1000,
    private val phiThreshold: Double = 8.0,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    
    // 节点怀疑级别
    private val suspicionLevels = ConcurrentHashMap<String, AtomicInteger>()
    
    // 心跳历史记录
    private val heartbeatHistory = ConcurrentHashMap<String, HeartbeatHistory>()
    
    // 故障状态缓存
    private val failureStates = ConcurrentHashMap<String, FailureType>()
    
    // 最后心跳时间
    private val lastHeartbeatTime = ConcurrentHashMap<String, AtomicLong>()
    
    // 故障检测监听器
    private val failureListeners = mutableListOf<FailureListener>()
    
    /**
     * 检测节点故障
     */
    suspend fun detectFailure(node: NodeInfo): FailureType {
        val nodeId = node.nodeId
        val startTime = System.nanoTime()
        
        try {
            val response = sendHeartbeat(node)
            val latency = (System.nanoTime() - startTime).nanoseconds
            
            // 更新心跳历史
            updateHeartbeatHistory(nodeId, latency, response.load)
            
            // 重置怀疑级别
            suspicionLevels[nodeId]?.set(0)
            lastHeartbeatTime[nodeId]?.set(System.currentTimeMillis())
            
            val failureType = when {
                latency > 10.milliseconds -> FailureType.SLOW_RESPONSE
                response.load > 0.9 -> FailureType.HIGH_LOAD
                else -> FailureType.HEALTHY
            }
            
            failureStates[nodeId] = failureType
            return failureType
            
        } catch (e: Exception) {
            return handleHeartbeatFailure(nodeId, e)
        }
    }
    
    /**
     * 处理心跳失败
     */
    private suspend fun handleHeartbeatFailure(nodeId: String, exception: Exception): FailureType {
        val suspicion = suspicionLevels.computeIfAbsent(nodeId) { AtomicInteger(0) }
        val currentSuspicion = suspicion.incrementAndGet()
        
        val failureType = when {
            currentSuspicion >= failureThreshold -> {
                // 使用Phi Accrual算法进行最终判断
                val phi = calculatePhi(nodeId)
                if (phi > phiThreshold) {
                    FailureType.CONFIRMED_FAILURE
                } else {
                    FailureType.NETWORK_FAILURE
                }
            }
            currentSuspicion >= failureThreshold / 2 -> FailureType.SUSPECTED
            else -> FailureType.NETWORK_FAILURE
        }
        
        failureStates[nodeId] = failureType
        
        // 通知故障监听器
        if (failureType == FailureType.CONFIRMED_FAILURE) {
            notifyFailureListeners(nodeId, failureType, exception)
        }
        
        return failureType
    }
    
    /**
     * 计算Phi值（Phi Accrual算法）
     */
    private fun calculatePhi(nodeId: String): Double {
        val history = heartbeatHistory[nodeId] ?: return Double.MAX_VALUE
        val lastHeartbeat = lastHeartbeatTime[nodeId]?.get() ?: return Double.MAX_VALUE
        
        val timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeat
        
        if (history.intervals.isEmpty()) {
            return if (timeSinceLastHeartbeat > heartbeatInterval.inWholeMilliseconds * 2) {
                Double.MAX_VALUE
            } else {
                0.0
            }
        }
        
        val mean = history.getMean()
        val variance = history.getVariance()
        val stdDev = kotlin.math.sqrt(variance)
        
        // 计算Phi值
        val y = (timeSinceLastHeartbeat - mean) / stdDev
        val phi = -kotlin.math.log10(1.0 - cumulativeDistributionFunction(y))
        
        return if (phi.isNaN() || phi.isInfinite()) Double.MAX_VALUE else phi
    }
    
    /**
     * 累积分布函数（正态分布）
     */
    private fun cumulativeDistributionFunction(x: Double): Double {
        return 0.5 * (1.0 + erf(x / kotlin.math.sqrt(2.0)))
    }
    
    /**
     * 误差函数近似
     */
    private fun erf(x: Double): Double {
        val a1 = 0.254829592
        val a2 = -0.284496736
        val a3 = 1.421413741
        val a4 = -1.453152027
        val a5 = 1.061405429
        val p = 0.3275911
        
        val sign = if (x < 0) -1 else 1
        val absX = kotlin.math.abs(x)
        
        val t = 1.0 / (1.0 + p * absX)
        val y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * kotlin.math.exp(-absX * absX)
        
        return sign * y
    }
    
    /**
     * 更新心跳历史
     */
    private fun updateHeartbeatHistory(nodeId: String, latency: Duration, load: Double) {
        val history = heartbeatHistory.computeIfAbsent(nodeId) { HeartbeatHistory(maxSampleSize) }
        val now = System.currentTimeMillis()
        
        history.addInterval(now, latency.inWholeMilliseconds, load)
    }
    
    /**
     * 发送心跳
     */
    private suspend fun sendHeartbeat(node: NodeInfo): HeartbeatResponse {
        // 模拟心跳发送，实际实现应该通过网络发送
        delay(1) // 模拟网络延迟
        
        return HeartbeatResponse(
            nodeId = node.nodeId,
            timestamp = System.currentTimeMillis(),
            load = kotlin.random.Random.nextDouble(0.0, 1.0), // 模拟负载
            latency = 1.milliseconds
        )
    }
    
    /**
     * 添加故障监听器
     */
    fun addFailureListener(listener: FailureListener) {
        failureListeners.add(listener)
    }
    
    /**
     * 移除故障监听器
     */
    fun removeFailureListener(listener: FailureListener) {
        failureListeners.remove(listener)
    }
    
    /**
     * 通知故障监听器
     */
    private fun notifyFailureListeners(nodeId: String, failureType: FailureType, exception: Exception) {
        scope.launch {
            failureListeners.forEach { listener ->
                try {
                    listener.onFailureDetected(nodeId, failureType, exception)
                } catch (e: Exception) {
                    // 记录监听器异常，但不影响其他监听器
                    println("Failure listener error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 获取节点当前状态
     */
    fun getNodeStatus(nodeId: String): FailureType {
        return failureStates[nodeId] ?: FailureType.HEALTHY
    }
    
    /**
     * 获取所有节点状态
     */
    fun getAllNodeStatus(): Map<String, FailureType> {
        return failureStates.toMap()
    }
    
    /**
     * 重置节点状态
     */
    fun resetNodeStatus(nodeId: String) {
        suspicionLevels[nodeId]?.set(0)
        failureStates[nodeId] = FailureType.HEALTHY
        lastHeartbeatTime[nodeId]?.set(System.currentTimeMillis())
    }
    
    /**
     * 清理过期数据
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val expireTime = now - 300_000 // 5分钟过期
        
        lastHeartbeatTime.entries.removeIf { (nodeId, lastTime) ->
            if (lastTime.get() < expireTime) {
                suspicionLevels.remove(nodeId)
                heartbeatHistory.remove(nodeId)
                failureStates.remove(nodeId)
                true
            } else {
                false
            }
        }
    }
}

/**
 * 心跳历史记录
 */
class HeartbeatHistory(private val maxSize: Int) {
    val intervals = mutableListOf<Long>()
    val loads = mutableListOf<Double>()
    private var lastTimestamp = 0L
    
    @Synchronized
    fun addInterval(timestamp: Long, latency: Long, load: Double) {
        if (lastTimestamp > 0) {
            val interval = timestamp - lastTimestamp
            intervals.add(interval)
            loads.add(load)
            
            // 保持固定大小
            if (intervals.size > maxSize) {
                intervals.removeAt(0)
                loads.removeAt(0)
            }
        }
        lastTimestamp = timestamp
    }
    
    fun getMean(): Double {
        return if (intervals.isEmpty()) 0.0 else intervals.average()
    }
    
    fun getVariance(): Double {
        if (intervals.size < 2) return 0.0
        
        val mean = getMean()
        return intervals.map { (it - mean) * (it - mean) }.average()
    }
    
    fun getAverageLoad(): Double {
        return if (loads.isEmpty()) 0.0 else loads.average()
    }
}

/**
 * 故障监听器接口
 */
interface FailureListener {
    suspend fun onFailureDetected(nodeId: String, failureType: FailureType, exception: Exception)
}
