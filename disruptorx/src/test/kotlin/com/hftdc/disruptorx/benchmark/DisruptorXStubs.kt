package com.hftdc.disruptorx

import com.hftdc.disruptorx.api.EventHandler
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.api.PartitionStrategy
import com.hftdc.disruptorx.api.Workflow
import com.hftdc.disruptorx.api.WorkflowSink
import com.hftdc.disruptorx.api.WorkflowSource
import com.hftdc.disruptorx.api.WorkflowStage
import com.hftdc.disruptorx.api.WorkflowStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

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
    val nodeId: String = "test-node",
    val host: String = "localhost",
    val port: Int = 9090,
    val nodeRole: NodeRole = NodeRole.COORDINATOR,
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
        private val workflows = ConcurrentHashMap<String, Workflow>()
        private val workflowStatuses = ConcurrentHashMap<String, WorkflowStatus>()
        
        fun createWorkflow(id: String): WorkflowImpl {
            val workflow = WorkflowImpl(id)
            workflows[id] = workflow
            workflowStatuses[id] = WorkflowStatus.CREATED
            return workflow
        }
        
        fun register(workflow: Workflow) {
            workflows[workflow.id] = workflow
            workflowStatuses[workflow.id] = WorkflowStatus.CREATED
        }
        
        fun start(id: String) {
            workflowStatuses[id] = WorkflowStatus.RUNNING
        }
        
        fun stop(id: String) {
            workflowStatuses[id] = WorkflowStatus.STOPPED
        }
        
        fun status(id: String): WorkflowStatus {
            return workflowStatuses[id] ?: WorkflowStatus.ERROR
        }
        
        fun getWorkflow(id: String): Workflow? {
            return workflows[id]
        }
        
        fun startWorkflow(id: String): CompletableFuture<Void> {
            start(id)
            return CompletableFuture.completedFuture(null)
        }
    }
}

/**
 * Workflow stub for tests
 */
class WorkflowImpl(override val id: String, override val name: String = "Test Workflow") : Workflow {
    // Simplified workflow structure for tests
    override val source = SourceImpl("orders")
    override val sink = SinkImpl("processed-orders")
    override val stages = listOf(
        StageImpl("validation", 1),
        StageImpl("enrichment", 1),
        StageImpl("processing", 4),
        StageImpl("notification", 1)
    )
    
    fun start(): CompletableFuture<Void> {
        return CompletableFuture.completedFuture(null)
    }
    
    class SourceImpl(override val topic: String) : WorkflowSource {
        override val partitionStrategy: PartitionStrategy = object : PartitionStrategy {
            override fun getPartition(key: Any, partitionCount: Int): Int = Math.abs(key.hashCode() % partitionCount)
        }
        
        fun partitionBy(partitioner: (Any) -> Int) {
            // Stub implementation
        }
    }
    
    class SinkImpl(override val topic: String) : WorkflowSink {
        // Stub implementation
    }
    
    class StageImpl(override val id: String, override val parallelism: Int) : WorkflowStage {
        private var handlerFn: (suspend (Any) -> Unit)? = null
        
        fun handler(handler: suspend (Any) -> Unit) {
            handlerFn = handler
        }
        
        override fun getHandler(): EventHandler<Any> {
            return object : EventHandler<Any> {
                override fun onEvent(event: Any, sequence: Long, endOfBatch: Boolean) {
                    // In a real implementation, this would execute the handler function
                }
            }
        }
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

/**
 * NodeRole enum for tests
 */
enum class NodeRole {
    COORDINATOR,
    WORKER
}