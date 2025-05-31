package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.DistributedSequence
import com.hftdc.disruptorx.api.NodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * 分布式序列实现
 * 扩展LMAX Disruptor的Sequence到分布式环境
 *
 * @property sequenceId 序列ID，用于在集群中唯一标识
 * @property nodeManager 节点管理器
 * @property nodeId 所属节点ID
 * @property initialValue 初始值
 * @property sequenceBroadcaster 序列值广播器，用于将本地序列值变更广播到其他节点
 */
class DistributedSequenceImpl(
    private val sequenceId: String,
    private val nodeManager: NodeManagerImpl,
    private val nodeId: String,
    initialValue: Long = -1,
    private val sequenceBroadcaster: SequenceBroadcaster
) : DistributedSequence, CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Default + job

    // 本地序列值
    private val localValue = AtomicLong(initialValue)

    // 最后广播的值
    private val lastBroadcastValue = AtomicLong(initialValue)

    // 广播阈值
    private val broadcastThreshold = 64L

    // 缓存本地节点判断结果
    private val isLocalNode: Boolean by lazy {
        nodeId == nodeManager.getLocalNodeId()
    }
    
    /**
     * 获取当前序列值
     * @return 当前序列值
     */
    override fun get(): Long {
        return localValue.get()
    }
    
    /**
     * 设置序列值
     * @param value 新的序列值
     */
    override fun set(value: Long) {
        localValue.set(value)

        // 如果是非本地序列，只更新本地值
        if (!isLocal()) {
            return
        }
        
        // 对于本地序列，检查是否需要广播更新
        if (shouldBroadcast(value)) {
            broadcastValue(value)
        }
    }
    
    /**
     * 增加序列值并返回
     * @return 增加后的序列值
     */
    override fun incrementAndGet(): Long {
        val newValue = localValue.incrementAndGet()
        
        // 如果是非本地序列，只更新本地值
        if (!isLocal()) {
            return newValue
        }
        
        // 对于本地序列，检查是否需要广播更新
        if (shouldBroadcast(newValue)) {
            broadcastValue(newValue)
        }
        
        return newValue
    }
    
    /**
     * 增加指定值并返回
     * @param increment 要增加的值
     * @return 增加后的序列值
     */
    override fun addAndGet(increment: Long): Long {
        val newValue = localValue.addAndGet(increment)
        
        // 如果是非本地序列，只更新本地值
        if (!isLocal()) {
            return newValue
        }
        
        // 对于本地序列，检查是否需要广播更新
        if (shouldBroadcast(newValue)) {
            broadcastValue(newValue)
        }
        
        return newValue
    }
    
    /**
     * 检查是否是本地序列
     * @return 是否本地序列
     */
    fun isLocal(): Boolean {
        return isLocalNode
    }
    
    /**
     * 更新远程序列值
     * 当接收到来自其他节点的序列更新时调用
     *
     * @param value 新的远程序列值
     */
    fun updateRemoteValue(value: Long) {
        // 只有非本地序列才会接收远程更新
        if (!isLocal()) {
            // 只有当新值大于当前值时才更新
            var current: Long
            do {
                current = localValue.get()
                if (value <= current) {
                    return
                }
            } while (!localValue.compareAndSet(current, value))
        }
    }
    
    /**
     * 检查是否应该广播序列值
     * @param newValue 新值
     * @return 是否应该广播
     */
    private fun shouldBroadcast(newValue: Long): Boolean {
        // 如果增量达到阈值或序列为初始值后的首次更新，则广播
        val lastBroadcast = lastBroadcastValue.get()
        return newValue - lastBroadcast >= broadcastThreshold ||
               (lastBroadcast == -1L && newValue > -1L)
    }
    
    /**
     * 广播序列值到其他节点
     * @param value 要广播的值
     */
    private fun broadcastValue(value: Long) {
        // 更新最后广播值
        lastBroadcastValue.set(value)
        
        // 异步广播
        launch {
            sequenceBroadcaster.broadcastSequenceValue(sequenceId, value)
        }
    }
}

/**
 * 序列值广播器接口
 * 负责将序列值变更广播到其他节点
 */
interface SequenceBroadcaster {
    /**
     * 广播序列值
     * @param sequenceId 序列ID
     * @param value 序列值
     */
    suspend fun broadcastSequenceValue(sequenceId: String, value: Long)
}

/**
 * 默认序列广播器实现
 * @property nodeManager 节点管理器
 */
class DefaultSequenceBroadcaster(
    private val nodeManager: NodeManagerImpl
) : SequenceBroadcaster {
    
    /**
     * 广播序列值
     * @param sequenceId 序列ID
     * @param value 序列值
     */
    override suspend fun broadcastSequenceValue(sequenceId: String, value: Long) {
        val members = nodeManager.getClusterMembers()
        val localNodeId = nodeManager.getLocalNodeId()
        
        // 广播到所有活跃的非本地节点
        val targetNodes = members.filter { 
            it.nodeId != localNodeId && 
            it.status == com.hftdc.disruptorx.api.NodeStatus.ACTIVE 
        }
        
        for (node in targetNodes) {
            try {
                // 实际实现应该发送网络请求将序列值发送到远程节点
                // 此处简化实现
            } catch (e: Exception) {
                // 处理异常，但不阻止继续广播到其他节点
                // TODO: 添加日志记录
            }
        }
    }
} 