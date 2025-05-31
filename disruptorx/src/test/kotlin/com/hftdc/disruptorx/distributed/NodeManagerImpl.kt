package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.NodeInfo
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.api.NodeStatus
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Node manager implementation for tests
 */
open class NodeManagerImpl(
    private val localNodeId: String,
    private val initialNodes: List<NodeInfo> = emptyList()
) {
    private val clusterMembers = ConcurrentHashMap<String, NodeInfo>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var isRunning = false

    init {
        // Initialize with provided nodes
        initialNodes.forEach { node ->
            clusterMembers[node.nodeId] = node
        }
    }
    
    fun initialize() {
        isRunning = true
        startHeartbeat()
    }
    
    fun shutdown() {
        isRunning = false
        scope.cancel()
        clusterMembers.clear()
    }
    
    /**
     * Get all cluster members
     */
    suspend fun getClusterMembers(): List<NodeInfo> {
        return clusterMembers.values.toList()
    }
    
    /**
     * Add a node to the cluster
     */
    suspend fun addNode(nodeInfo: NodeInfo) {
        clusterMembers[nodeInfo.nodeId] = nodeInfo
    }
    
    /**
     * Remove a node from the cluster
     */
    suspend fun removeNode(nodeId: String) {
        clusterMembers.remove(nodeId)
    }
    
    /**
     * Update node status
     */
    suspend fun updateNodeStatus(nodeId: String, status: NodeStatus) {
        val node = clusterMembers[nodeId]
        if (node != null) {
            clusterMembers[nodeId] = node.copy(status = status)
        }
    }
    
    /**
     * Get node information
     */
    suspend fun getNode(nodeId: String): NodeInfo? {
        return clusterMembers[nodeId]
    }
    
    /**
     * Get active nodes
     */
    suspend fun getActiveNodes(): List<NodeInfo> {
        return clusterMembers.values.filter { it.status == NodeStatus.ACTIVE }
    }
    
    /**
     * Get leader node
     */
    suspend fun getLeaderNode(): NodeInfo? {
        return clusterMembers.values.find { it.isLeader }
    }
    
    /**
     * Set node as leader
     */
    suspend fun setLeader(nodeId: String) {
        // First, remove leader status from all nodes
        clusterMembers.values.forEach { node ->
            if (node.isLeader) {
                clusterMembers[node.nodeId] = node.copy(isLeader = false)
            }
        }
        
        // Set the specified node as leader
        val node = clusterMembers[nodeId]
        if (node != null) {
            clusterMembers[nodeId] = node.copy(isLeader = true)
        }
    }
    
    /**
     * Check if local node is leader
     */
    suspend fun isLocalNodeLeader(): Boolean {
        val localNode = clusterMembers[localNodeId]
        return localNode?.isLeader == true
    }
    
    /**
     * Get cluster size
     */
    suspend fun getClusterSize(): Int {
        return clusterMembers.size
    }
    
    /**
     * Start heartbeat monitoring
     */
    private fun startHeartbeat() {
        scope.launch {
            while (isRunning) {
                try {
                    // Simulate heartbeat processing
                    delay(1000) // 1 second heartbeat interval
                    
                    // In a real implementation, this would:
                    // 1. Send heartbeats to other nodes
                    // 2. Check for failed nodes
                    // 3. Update node statuses
                    
                } catch (e: Exception) {
                    if (isRunning) {
                        println("Error in heartbeat: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Simulate node failure (for testing)
     */
    suspend fun simulateNodeFailure(nodeId: String) {
        updateNodeStatus(nodeId, NodeStatus.FAILED)
    }
    
    /**
     * Simulate node recovery (for testing)
     */
    suspend fun simulateNodeRecovery(nodeId: String) {
        updateNodeStatus(nodeId, NodeStatus.ACTIVE)
    }
    
    /**
     * Get cluster statistics
     */
    suspend fun getClusterStats(): Map<String, Any> {
        val nodes = clusterMembers.values
        val activeCount = nodes.count { it.status == NodeStatus.ACTIVE }
        val failedCount = nodes.count { it.status == NodeStatus.FAILED }
        val leaderCount = nodes.count { it.isLeader }
        
        return mapOf(
            "totalNodes" to nodes.size,
            "activeNodes" to activeCount,
            "failedNodes" to failedCount,
            "leaderNodes" to leaderCount,
            "localNodeId" to localNodeId
        )
    }
}
