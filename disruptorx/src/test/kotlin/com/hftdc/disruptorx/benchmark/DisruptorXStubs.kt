package com.hftdc.disruptorx

import com.hftdc.disruptorx.api.NodeRole
import java.util.concurrent.CompletableFuture

/**
 * DisruptorX stub implementation for tests
 */
object DisruptorX {
    fun createNode(config: DisruptorXConfig): DisruptorXNode {
        return DisruptorXNode(config)
    }
}

/**
 * DisruptorX configuration stub for tests
 */
data class DisruptorXConfig(
    val nodeId: String,
    val host: String,
    val port: Int,
    val nodeRole: NodeRole,
    val seedNodes: List<String> = emptyList()
)

/**
 * DisruptorX node stub for tests
 */
class DisruptorXNode(val config: DisruptorXConfig) {
    val nodeId: String = config.nodeId
    val eventBus: EventBus = EventBus()
    val workflowManager: WorkflowManager = WorkflowManager()
    
    fun initialize() {
        // Stub implementation
    }
    
    fun shutdown() {
        // Stub implementation
    }
    
    /**
     * Event bus stub for tests
     */
    class EventBus {
        fun subscribe(topic: String, handler: suspend (Any) -> Unit) {
            // Stub implementation
        }
        
        fun publish(message: Any, topic: String) {
            // Stub implementation
        }
    }
    
    /**
     * Workflow manager stub for tests
     */
    class WorkflowManager {
        fun createWorkflow(id: String): Workflow {
            return Workflow(id)
        }
        
        fun startWorkflow(id: String): CompletableFuture<Void> {
            return CompletableFuture.completedFuture(null)
        }
    }
}

/**
 * Workflow stub for tests
 */
class Workflow(val id: String) {
    fun start(): CompletableFuture<Void> {
        return CompletableFuture.completedFuture(null)
    }
}

/**
 * BenchmarkMessage stub for tests
 */
data class BenchmarkMessage(
    val payload: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as BenchmarkMessage
        
        if (!payload.contentEquals(other.payload)) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        return payload.contentHashCode()
    }
} 