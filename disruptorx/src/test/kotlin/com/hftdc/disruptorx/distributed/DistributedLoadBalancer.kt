package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.NodeInfo
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cluster load statistics
 */
data class ClusterLoadStats(
    val totalNodes: Int,
    val activeNodes: Int,
    val totalCpuUsage: Double,
    val totalMemoryUsage: Double,
    val totalQueueDepth: Int,
    val averageLatency: Double
)

/**
 * Node load information
 */
data class NodeLoad(
    val nodeId: String,
    val cpuUsage: Double,
    val memoryUsage: Double,
    val queueDepth: Int,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Distributed load balancer for tests
 */
class DistributedLoadBalancer(
    private val localNodeId: String,
    private val clusterNodes: List<NodeInfo>
) {
    private val nodeLoads = ConcurrentHashMap<String, NodeLoad>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val requestCounter = AtomicInteger(0)
    
    @Volatile
    private var isRunning = false
    
    fun initialize() {
        isRunning = true
        startLoadMonitoring()
    }
    
    fun shutdown() {
        isRunning = false
        scope.cancel()
        nodeLoads.clear()
    }
    
    /**
     * Update node load information
     */
    suspend fun updateNodeLoad(
        nodeId: String,
        cpuUsage: Double,
        memoryUsage: Double,
        queueDepth: Int
    ) {
        val nodeLoad = NodeLoad(
            nodeId = nodeId,
            cpuUsage = cpuUsage,
            memoryUsage = memoryUsage,
            queueDepth = queueDepth,
            lastUpdated = System.currentTimeMillis()
        )
        
        nodeLoads[nodeId] = nodeLoad
    }
    
    /**
     * Get the best node for processing based on load
     */
    fun selectBestNode(): String? {
        val activeNodes = getActiveNodes()
        if (activeNodes.isEmpty()) return null
        
        // Simple load balancing: select node with lowest queue depth
        return activeNodes.minByOrNull { it.queueDepth }?.nodeId
    }
    
    /**
     * Get cluster load statistics
     */
    fun getClusterLoadStats(): ClusterLoadStats {
        val activeNodes = getActiveNodes()
        
        if (activeNodes.isEmpty()) {
            return ClusterLoadStats(
                totalNodes = clusterNodes.size,
                activeNodes = 0,
                totalCpuUsage = 0.0,
                totalMemoryUsage = 0.0,
                totalQueueDepth = 0,
                averageLatency = 0.0
            )
        }
        
        val totalCpuUsage = activeNodes.sumOf { it.cpuUsage }
        val totalMemoryUsage = activeNodes.sumOf { it.memoryUsage }
        val totalQueueDepth = activeNodes.sumOf { it.queueDepth }
        
        return ClusterLoadStats(
            totalNodes = clusterNodes.size,
            activeNodes = activeNodes.size,
            totalCpuUsage = totalCpuUsage,
            totalMemoryUsage = totalMemoryUsage,
            totalQueueDepth = totalQueueDepth,
            averageLatency = 0.0 // Simplified for tests
        )
    }
    
    /**
     * Get load information for a specific node
     */
    fun getNodeLoad(nodeId: String): NodeLoad? {
        return nodeLoads[nodeId]
    }
    
    /**
     * Get all active nodes (nodes that have reported load recently)
     */
    private fun getActiveNodes(): List<NodeLoad> {
        val currentTime = System.currentTimeMillis()
        val activeThreshold = 30000L // 30 seconds
        
        return nodeLoads.values.filter { 
            currentTime - it.lastUpdated < activeThreshold 
        }
    }
    
    /**
     * Route request to the best available node
     */
    fun routeRequest(request: Any): String? {
        val bestNode = selectBestNode()
        if (bestNode != null) {
            requestCounter.incrementAndGet()
        }
        return bestNode
    }
    
    /**
     * Get routing statistics
     */
    fun getRoutingStats(): Map<String, Any> {
        return mapOf(
            "totalRequests" to requestCounter.get(),
            "activeNodes" to getActiveNodes().size,
            "nodeLoads" to nodeLoads.toMap()
        )
    }
    
    /**
     * Start background load monitoring
     */
    private fun startLoadMonitoring() {
        scope.launch {
            while (isRunning) {
                try {
                    cleanupStaleNodes()
                    delay(10000) // Cleanup every 10 seconds
                } catch (e: Exception) {
                    if (isRunning) {
                        println("Error in load monitoring: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Remove nodes that haven't reported load recently
     */
    private fun cleanupStaleNodes() {
        val currentTime = System.currentTimeMillis()
        val staleThreshold = 60000L // 60 seconds
        
        val staleNodes = nodeLoads.values.filter { 
            currentTime - it.lastUpdated > staleThreshold 
        }
        
        staleNodes.forEach { node ->
            nodeLoads.remove(node.nodeId)
        }
        
        if (staleNodes.isNotEmpty()) {
            println("Removed ${staleNodes.size} stale nodes from load balancer")
        }
    }
    
    /**
     * Force update node status (for testing)
     */
    fun forceUpdateNodeStatus(nodeId: String, isActive: Boolean) {
        if (isActive) {
            // Add a default load entry if node doesn't exist
            nodeLoads.putIfAbsent(nodeId, NodeLoad(
                nodeId = nodeId,
                cpuUsage = 0.5,
                memoryUsage = 0.6,
                queueDepth = 10
            ))
        } else {
            nodeLoads.remove(nodeId)
        }
    }
}
