package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.NodeInfo
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.api.NodeStatus
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * 分区信息
 */
data class PartitionInfo(
    val partitionId: String,
    val primaryNode: String,
    val replicaNodes: List<String>,
    val status: PartitionStatus = PartitionStatus.ACTIVE
)

/**
 * 分区状态
 */
enum class PartitionStatus {
    ACTIVE,      // 活跃状态
    DEGRADED,    // 降级状态（部分副本不可用）
    UNAVAILABLE, // 不可用状态
    RECOVERING   // 恢复中
}

/**
 * 故障转移事件
 */
data class FailoverEvent(
    val eventId: String,
    val failedNodeId: String,
    val affectedPartitions: List<String>,
    val newPrimaryAssignments: Map<String, String>,
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String
)

/**
 * 自动故障转移管理器
 * 负责处理节点故障时的自动故障转移，包括：
 * 1. 主节点故障转移
 * 2. 副本重新分配
 * 3. 路由表更新
 * 4. 故障恢复
 */
class AutoFailover(
    private val nodeRegistry: NodeRegistry,
    private val partitionManager: PartitionManager,
    private val routingTable: RoutingTable,
    private val auditLogger: AuditLogger,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    
    // 故障转移状态
    private val failoverInProgress = AtomicBoolean(false)
    
    // 故障转移历史
    private val failoverHistory = mutableListOf<FailoverEvent>()
    
    // 故障转移监听器
    private val failoverListeners = mutableListOf<FailoverListener>()
    
    // 节点恢复监控
    private val recoveryMonitors = ConcurrentHashMap<String, Job>()
    
    /**
     * 处理节点故障
     */
    suspend fun handleNodeFailure(failedNode: NodeInfo, reason: String = "Node failure detected") {
        if (!failoverInProgress.compareAndSet(false, true)) {
            println("Failover already in progress, skipping...")
            return
        }
        
        try {
            println("Starting failover for node: ${failedNode.nodeId}")
            
            val startTime = System.currentTimeMillis()
            val affectedPartitions = partitionManager.getPartitions(failedNode.nodeId)
            
            if (affectedPartitions.isEmpty()) {
                println("No partitions affected by node failure: ${failedNode.nodeId}")
                return
            }
            
            // 1. 标记节点为不可用
            nodeRegistry.markUnavailable(failedNode.nodeId)
            
            // 2. 处理受影响的分区
            val newPrimaryAssignments = mutableMapOf<String, String>()
            
            for (partition in affectedPartitions) {
                val newPrimary = handlePartitionFailover(partition, failedNode.nodeId)
                if (newPrimary != null) {
                    newPrimaryAssignments[partition.partitionId] = newPrimary
                }
            }
            
            // 3. 更新路由表
            routingTable.removeNode(failedNode.nodeId)
            
            // 4. 广播拓扑变化
            broadcastTopologyChange(failedNode.nodeId, newPrimaryAssignments)
            
            // 5. 记录故障转移事件
            val failoverEvent = FailoverEvent(
                eventId = generateEventId(),
                failedNodeId = failedNode.nodeId,
                affectedPartitions = affectedPartitions.map { it.partitionId },
                newPrimaryAssignments = newPrimaryAssignments,
                reason = reason
            )
            
            recordFailoverEvent(failoverEvent)
            
            // 6. 启动节点恢复监控
            startRecoveryMonitoring(failedNode.nodeId)
            
            val duration = System.currentTimeMillis() - startTime
            println("Failover completed for node ${failedNode.nodeId} in ${duration}ms")
            
            // 通知监听器
            notifyFailoverListeners(failoverEvent)
            
        } catch (e: Exception) {
            println("Failover failed for node ${failedNode.nodeId}: ${e.message}")
            auditLogger.logError("Failover failed", mapOf(
                "nodeId" to failedNode.nodeId,
                "error" to e.message
            ))
            throw e
        } finally {
            failoverInProgress.set(false)
        }
    }
    
    /**
     * 处理分区故障转移
     */
    private suspend fun handlePartitionFailover(partition: PartitionInfo, failedNodeId: String): String? {
        return when {
            partition.primaryNode == failedNodeId -> {
                // 主节点故障，需要选举新的主节点
                val newPrimary = selectNewPrimary(partition)
                if (newPrimary != null) {
                    partitionManager.promoteReplica(partition.partitionId, newPrimary)
                    println("Promoted replica $newPrimary to primary for partition ${partition.partitionId}")
                    newPrimary
                } else {
                    // 没有可用的副本，分区进入不可用状态
                    partitionManager.markPartitionUnavailable(partition.partitionId)
                    println("No available replicas for partition ${partition.partitionId}, marking as unavailable")
                    null
                }
            }
            partition.replicaNodes.contains(failedNodeId) -> {
                // 副本节点故障，尝试创建新的副本
                val newReplica = selectNewReplica(partition, failedNodeId)
                if (newReplica != null) {
                    partitionManager.addReplica(partition.partitionId, newReplica)
                    println("Added new replica $newReplica for partition ${partition.partitionId}")
                }
                null // 副本故障不需要返回新的主节点
            }
            else -> {
                println("Node $failedNodeId not involved in partition ${partition.partitionId}")
                null
            }
        }
    }
    
    /**
     * 选择新的主节点
     */
    private suspend fun selectNewPrimary(partition: PartitionInfo): String? {
        val availableReplicas = partition.replicaNodes.filter { nodeId ->
            nodeRegistry.isNodeAvailable(nodeId)
        }
        
        if (availableReplicas.isEmpty()) {
            return null
        }
        
        // 选择负载最低的副本作为新的主节点
        return availableReplicas.minByOrNull { nodeId ->
            nodeRegistry.getNodeLoad(nodeId)
        }
    }
    
    /**
     * 选择新的副本节点
     */
    private suspend fun selectNewReplica(partition: PartitionInfo, failedNodeId: String): String? {
        val excludeNodes = setOf(partition.primaryNode) + partition.replicaNodes - failedNodeId
        val availableNodes = nodeRegistry.getAvailableNodes()
            .filter { !excludeNodes.contains(it.nodeId) }
        
        if (availableNodes.isEmpty()) {
            return null
        }
        
        // 选择负载最低的节点作为新副本
        return availableNodes.minByOrNull { node ->
            nodeRegistry.getNodeLoad(node.nodeId)
        }?.nodeId
    }
    
    /**
     * 广播拓扑变化
     */
    private suspend fun broadcastTopologyChange(failedNodeId: String, newAssignments: Map<String, String>) {
        val message = TopologyChangeMessage(
            type = "NODE_FAILURE",
            failedNodeId = failedNodeId,
            newPrimaryAssignments = newAssignments,
            timestamp = System.currentTimeMillis()
        )
        
        // 向所有活跃节点广播变化
        nodeRegistry.getAvailableNodes().forEach { node ->
            scope.launch {
                try {
                    sendTopologyChangeMessage(node, message)
                } catch (e: Exception) {
                    println("Failed to send topology change to ${node.nodeId}: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 发送拓扑变化消息
     */
    private suspend fun sendTopologyChangeMessage(node: NodeInfo, message: TopologyChangeMessage) {
        // 模拟发送消息，实际实现应该通过网络发送
        delay(10) // 模拟网络延迟
        println("Sent topology change to ${node.nodeId}: $message")
    }
    
    /**
     * 启动节点恢复监控
     */
    private fun startRecoveryMonitoring(nodeId: String) {
        // 取消之前的监控任务
        recoveryMonitors[nodeId]?.cancel()
        
        val monitorJob = scope.launch {
            var attempts = 0
            val maxAttempts = 60 // 最多监控5分钟（每5秒检查一次）
            
            while (attempts < maxAttempts) {
                delay(5.seconds)
                attempts++
                
                try {
                    if (nodeRegistry.isNodeAvailable(nodeId)) {
                        println("Node $nodeId has recovered, starting reintegration")
                        handleNodeRecovery(nodeId)
                        break
                    }
                } catch (e: Exception) {
                    println("Error checking node recovery for $nodeId: ${e.message}")
                }
            }
            
            if (attempts >= maxAttempts) {
                println("Node $nodeId recovery monitoring timeout")
            }
            
            recoveryMonitors.remove(nodeId)
        }
        
        recoveryMonitors[nodeId] = monitorJob
    }
    
    /**
     * 处理节点恢复
     */
    private suspend fun handleNodeRecovery(nodeId: String) {
        try {
            println("Handling recovery for node: $nodeId")
            
            // 1. 重新标记节点为可用
            nodeRegistry.markAvailable(nodeId)
            
            // 2. 重新添加到路由表
            val nodeInfo = nodeRegistry.getNodeInfo(nodeId)
            if (nodeInfo != null) {
                routingTable.addNode(nodeInfo)
            }
            
            // 3. 考虑重新平衡分区（可选）
            // partitionManager.rebalancePartitions()
            
            // 4. 记录恢复事件
            auditLogger.logRecovery(nodeId, System.currentTimeMillis())
            
            println("Node $nodeId successfully reintegrated")
            
        } catch (e: Exception) {
            println("Failed to handle recovery for node $nodeId: ${e.message}")
        }
    }
    
    /**
     * 记录故障转移事件
     */
    private fun recordFailoverEvent(event: FailoverEvent) {
        synchronized(failoverHistory) {
            failoverHistory.add(event)
            
            // 保持历史记录大小
            if (failoverHistory.size > 1000) {
                failoverHistory.removeAt(0)
            }
        }
        
        auditLogger.logFailover(event.failedNodeId, event.timestamp)
    }
    
    /**
     * 添加故障转移监听器
     */
    fun addFailoverListener(listener: FailoverListener) {
        failoverListeners.add(listener)
    }
    
    /**
     * 通知故障转移监听器
     */
    private suspend fun notifyFailoverListeners(event: FailoverEvent) {
        failoverListeners.forEach { listener ->
            scope.launch {
                try {
                    listener.onFailoverCompleted(event)
                } catch (e: Exception) {
                    println("Failover listener error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 获取故障转移历史
     */
    fun getFailoverHistory(): List<FailoverEvent> {
        return synchronized(failoverHistory) {
            failoverHistory.toList()
        }
    }
    
    /**
     * 生成事件ID
     */
    private fun generateEventId(): String {
        return "failover-${System.currentTimeMillis()}-${kotlin.random.Random.nextInt(1000, 9999)}"
    }
    
    /**
     * 清理资源
     */
    fun shutdown() {
        recoveryMonitors.values.forEach { it.cancel() }
        recoveryMonitors.clear()
    }
}

/**
 * 拓扑变化消息
 */
data class TopologyChangeMessage(
    val type: String,
    val failedNodeId: String,
    val newPrimaryAssignments: Map<String, String>,
    val timestamp: Long
)

/**
 * 故障转移监听器
 */
interface FailoverListener {
    suspend fun onFailoverCompleted(event: FailoverEvent)
}

/**
 * 模拟接口定义（实际实现中这些应该在其他文件中）
 */
interface NodeRegistry {
    suspend fun markUnavailable(nodeId: String)
    suspend fun markAvailable(nodeId: String)
    suspend fun isNodeAvailable(nodeId: String): Boolean
    suspend fun getNodeLoad(nodeId: String): Double
    suspend fun getAvailableNodes(): List<NodeInfo>
    suspend fun getNodeInfo(nodeId: String): NodeInfo?
}

interface PartitionManager {
    suspend fun getPartitions(nodeId: String): List<PartitionInfo>
    suspend fun promoteReplica(partitionId: String, newPrimaryNodeId: String)
    suspend fun markPartitionUnavailable(partitionId: String)
    suspend fun addReplica(partitionId: String, replicaNodeId: String)
}

interface RoutingTable {
    suspend fun removeNode(nodeId: String)
    suspend fun addNode(node: NodeInfo)
}

interface AuditLogger {
    fun logFailover(nodeId: String, timestamp: Long)
    fun logRecovery(nodeId: String, timestamp: Long)
    fun logError(message: String, context: Map<String, Any?>)
}
