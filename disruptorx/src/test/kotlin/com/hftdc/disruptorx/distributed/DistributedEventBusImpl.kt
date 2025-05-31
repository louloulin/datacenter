package com.hftdc.disruptorx.distributed

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Distributed event bus implementation for tests
 */
class DistributedEventBusImpl(
    private val nodeManager: NodeManagerImpl,
    private val localNodeId: String,
    private val config: DistributedEventBusConfig
) {
    private val localSubscriptions = ConcurrentHashMap<String, MutableList<suspend (Any) -> Unit>>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    @Volatile
    private var isRunning = false
    
    @Volatile
    private var isInitialized = false
    
    /**
     * Initialize the distributed event bus
     */
    suspend fun initialize() {
        if (isInitialized) return
        
        try {
            isRunning = true
            
            // Get cluster members to verify connectivity
            val clusterMembers = nodeManager.getClusterMembers()
            println("Initialized distributed event bus with ${clusterMembers.size} cluster members")
            
            isInitialized = true
        } catch (e: Exception) {
            println("Failed to initialize distributed event bus: ${e.message}")
            throw e
        }
    }
    
    /**
     * Shutdown the distributed event bus
     */
    fun shutdown() {
        isRunning = false
        isInitialized = false
        scope.cancel()
        localSubscriptions.clear()
    }
    
    /**
     * Subscribe to a topic
     */
    fun subscribe(topic: String, handler: suspend (Any) -> Unit) {
        if (!isRunning) {
            throw IllegalStateException("Event bus is not running")
        }
        
        localSubscriptions.computeIfAbsent(topic) { mutableListOf() }.add(handler)
    }
    
    /**
     * Unsubscribe from a topic
     */
    fun unsubscribe(topic: String, handler: suspend (Any) -> Unit) {
        localSubscriptions[topic]?.remove(handler)
    }
    
    /**
     * Publish an event to a topic
     */
    fun publish(event: Any, topic: String) {
        if (!isRunning) {
            throw IllegalStateException("Event bus is not running")
        }
        
        // Handle local subscriptions
        val handlers = localSubscriptions[topic] ?: emptyList()
        
        scope.launch {
            try {
                // Deliver to local handlers
                handlers.forEach { handler ->
                    try {
                        handler(event)
                    } catch (e: Exception) {
                        println("Error in local event handler: ${e.message}")
                    }
                }
                
                // In a real implementation, this would also:
                // 1. Route events to remote nodes based on topic partitioning
                // 2. Handle replication based on configuration
                // 3. Ensure delivery guarantees
                
                // For testing, we simulate routing to cluster members
                val clusterMembers = nodeManager.getClusterMembers()
                clusterMembers.forEach { node ->
                    if (node.nodeId != localNodeId) {
                        // Simulate sending to remote node
                        println("Routing event to node: ${node.nodeId}")
                    }
                }
                
            } catch (e: Exception) {
                println("Error publishing event: ${e.message}")
            }
        }
    }
    
    /**
     * Get subscription count for a topic
     */
    fun getSubscriptionCount(topic: String): Int {
        return localSubscriptions[topic]?.size ?: 0
    }
    
    /**
     * Get all subscribed topics
     */
    fun getSubscribedTopics(): Set<String> {
        return localSubscriptions.keys.toSet()
    }
    
    /**
     * Check if the event bus is running
     */
    fun isRunning(): Boolean {
        return isRunning
    }
    
    /**
     * Check if the event bus is initialized
     */
    fun isInitialized(): Boolean {
        return isInitialized
    }
    
    /**
     * Get event bus statistics
     */
    suspend fun getStatistics(): Map<String, Any> {
        val clusterMembers = nodeManager.getClusterMembers()
        val totalSubscriptions = localSubscriptions.values.sumOf { it.size }
        
        return mapOf(
            "localNodeId" to localNodeId,
            "isRunning" to isRunning,
            "isInitialized" to isInitialized,
            "clusterSize" to clusterMembers.size,
            "subscribedTopics" to localSubscriptions.keys.size,
            "totalSubscriptions" to totalSubscriptions,
            "config" to mapOf(
                "replicationFactor" to config.replicationFactor,
                "enableCompression" to config.enableCompression,
                "heartbeatInterval" to config.heartbeatIntervalMs
            )
        )
    }
    
    /**
     * Force trigger event routing (for testing)
     */
    suspend fun triggerEventRouting(event: Any, topic: String) {
        val clusterMembers = nodeManager.getClusterMembers()
        println("Triggering event routing for topic '$topic' to ${clusterMembers.size} nodes")
        
        // Simulate event routing logic
        clusterMembers.forEach { node ->
            if (node.nodeId != localNodeId) {
                println("Routing event to ${node.nodeId}: $event")
            }
        }
    }
    
    /**
     * Simulate network partition (for testing)
     */
    fun simulateNetworkPartition(affectedNodes: List<String>) {
        println("Simulating network partition affecting nodes: $affectedNodes")
        // In a real implementation, this would affect routing decisions
    }
    
    /**
     * Simulate network recovery (for testing)
     */
    fun simulateNetworkRecovery() {
        println("Simulating network recovery")
        // In a real implementation, this would restore normal routing
    }
}
