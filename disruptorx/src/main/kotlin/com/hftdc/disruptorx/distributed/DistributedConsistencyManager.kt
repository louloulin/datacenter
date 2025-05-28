package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.NodeInfo
import com.hftdc.disruptorx.consensus.RaftConsensus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * 分布式一致性管理器
 * 基于 Raft 协议确保跨节点的强一致性
 */
class DistributedConsistencyManager(
    private val nodeId: String,
    private val clusterNodes: List<NodeInfo>
) : CoroutineScope {
    
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Default + job
    
    // Raft 共识节点
    private val raftNode = RaftConsensus(nodeId, clusterNodes)
    
    // 分布式事件日志
    private val eventLog = DistributedEventLog()
    
    // 快照管理器
    private val snapshotManager = SnapshotManager()
    
    /**
     * 启动一致性管理器
     */
    suspend fun start() {
        raftNode.start()
        
        // 启动日志压缩协程
        launch {
            startLogCompaction()
        }
    }
    
    /**
     * 停止一致性管理器
     */
    suspend fun stop() {
        raftNode.stopWithHttpServer()
        job.cancel()
    }
    
    /**
     * 分布式事件提交
     * 确保跨节点的强一致性
     */
    suspend fun commitEvent(event: DistributedEvent): ConsistencyResult {
        return try {
            // 将事件序列化为字节数组
            val eventData = event.payload.toByteArray()
            
            // 通过 Raft 协议提交事件
            val index = raftNode.propose(eventData)
            
            // 创建日志条目
            val logEntry = EventLogEntry(
                term = getCurrentTerm(),
                index = index,
                event = event,
                timestamp = System.currentTimeMillis()
            )
            
            // 应用到本地状态机
            eventLog.appendEntry(logEntry)
            ConsistencyResult.SUCCESS
        } catch (e: Exception) {
            ConsistencyResult.FAILED
        }
    }
    
    /**
     * 分布式快照管理
     * 支持快速故障恢复
     */
    suspend fun createSnapshot(): SnapshotResult {
        return try {
            val snapshot = ClusterSnapshot(
                term = getCurrentTerm(),
                index = eventLog.getLastIndex(),
                state = captureClusterState(),
                timestamp = System.currentTimeMillis()
            )
            
            snapshotManager.saveSnapshot(snapshot)
        } catch (e: Exception) {
            SnapshotResult(
                isSuccess = false,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * 从快照恢复状态
     */
    suspend fun restoreFromSnapshot(snapshotId: String): Boolean {
        val snapshot = snapshotManager.loadSnapshot(snapshotId) ?: return false
        
        // 恢复集群状态
        restoreClusterState(snapshot.state)
        
        // 重置事件日志
        eventLog.truncateToIndex(snapshot.index)
        
        return true
    }
    
    /**
     * 获取一致性状态
     */
    fun getConsistencyStatus(): ConsistencyStatus {
        return ConsistencyStatus(
            isLeader = raftNode.isLeader(),
            currentTerm = getCurrentTerm(),
            commitIndex = eventLog.getCommitIndex(),
            lastApplied = eventLog.getLastAppliedIndex(),
            clusterSize = clusterNodes.size,
            activeNodes = getActiveNodeCount()
        )
    }
    
    /**
     * 获取当前任期
     */
    private fun getCurrentTerm(): Long {
        return raftNode.getCurrentTerm()
    }
    
    /**
     * 获取活跃节点数量
     */
    private fun getActiveNodeCount(): Int {
        return clusterNodes.size // 简化实现，实际应该检查节点状态
    }
    
    /**
     * 启动日志压缩
     */
    private suspend fun startLogCompaction() {
        while (true) {
            kotlinx.coroutines.delay(60000) // 每分钟检查一次
            
            if (eventLog.shouldCompact()) {
                val snapshot = createSnapshot()
                if (snapshot.isSuccess) {
                    eventLog.compactToIndex(snapshot.snapshotIndex)
                }
            }
        }
    }
    
    /**
     * 捕获集群状态
     */
    private fun captureClusterState(): ClusterState {
        return ClusterState(
            nodes = clusterNodes.map { it.copy() },
            eventLogSize = eventLog.size(),
            lastEventTimestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * 恢复集群状态
     */
    private fun restoreClusterState(state: ClusterState) {
        // 实现状态恢复逻辑
        // 这里可以根据具体需求实现
    }
}

/**
 * 分布式事件日志
 */
class DistributedEventLog {
    private val entries = ConcurrentHashMap<Long, EventLogEntry>()
    private val nextIndex = AtomicLong(1)
    private val commitIndex = AtomicLong(0)
    private val lastAppliedIndex = AtomicLong(0)
    
    fun getNextIndex(): Long = nextIndex.get()
    
    fun appendEntry(entry: EventLogEntry) {
        entries[entry.index] = entry
        nextIndex.set(entry.index + 1)
    }
    
    fun getEntry(index: Long): EventLogEntry? = entries[index]
    
    fun getLastIndex(): Long = nextIndex.get() - 1
    
    fun getCommitIndex(): Long = commitIndex.get()
    
    fun getLastAppliedIndex(): Long = lastAppliedIndex.get()
    
    fun setCommitIndex(index: Long) {
        commitIndex.set(index)
    }
    
    fun setLastAppliedIndex(index: Long) {
        lastAppliedIndex.set(index)
    }
    
    fun size(): Int = entries.size
    
    fun shouldCompact(): Boolean {
        return entries.size > 10000 // 当日志条目超过10000时进行压缩
    }
    
    fun compactToIndex(index: Long) {
        entries.keys.removeIf { it <= index }
    }
    
    fun truncateToIndex(index: Long) {
        entries.keys.removeIf { it > index }
        nextIndex.set(index + 1)
    }
}

/**
 * 快照管理器
 */
class SnapshotManager {
    private val snapshots = ConcurrentHashMap<String, ClusterSnapshot>()
    
    suspend fun saveSnapshot(snapshot: ClusterSnapshot): SnapshotResult {
        val snapshotId = "snapshot-${snapshot.timestamp}"
        snapshots[snapshotId] = snapshot
        
        return SnapshotResult(
            isSuccess = true,
            snapshotId = snapshotId,
            snapshotIndex = snapshot.index
        )
    }
    
    fun loadSnapshot(snapshotId: String): ClusterSnapshot? {
        return snapshots[snapshotId]
    }
    
    fun listSnapshots(): List<String> {
        return snapshots.keys.toList()
    }
}

/**
 * 数据类定义
 */
@Serializable
data class DistributedEvent(
    val eventId: String,
    val eventType: String,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class EventLogEntry(
    val term: Long,
    val index: Long,
    val event: DistributedEvent,
    val timestamp: Long
)

@Serializable
data class ClusterSnapshot(
    val term: Long,
    val index: Long,
    val state: ClusterState,
    val timestamp: Long
)

@Serializable
data class ClusterState(
    val nodes: List<NodeInfo>,
    val eventLogSize: Int,
    val lastEventTimestamp: Long
)

data class ConsistencyResult(
    val isSuccess: Boolean,
    val errorMessage: String? = null
) {
    companion object {
        val SUCCESS = ConsistencyResult(true)
        val FAILED = ConsistencyResult(false, "Consensus failed")
    }
}

data class SnapshotResult(
    val isSuccess: Boolean,
    val snapshotId: String? = null,
    val snapshotIndex: Long = 0,
    val errorMessage: String? = null
)

data class ConsistencyStatus(
    val isLeader: Boolean,
    val currentTerm: Long,
    val commitIndex: Long,
    val lastApplied: Long,
    val clusterSize: Int,
    val activeNodes: Int
)