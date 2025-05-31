package com.hftdc.disruptorx.api

/**
 * Node information for tests
 */
data class NodeInfo(
    val nodeId: String,
    val host: String,
    val port: Int,
    val isLeader: Boolean = false,
    val role: NodeRole,
    val status: NodeStatus
)

/**
 * Node role enumeration
 */
enum class NodeRole {
    COORDINATOR,
    WORKER,
    MIXED
}

/**
 * Node status enumeration
 */
enum class NodeStatus {
    ACTIVE,
    INACTIVE,
    JOINING,
    LEAVING,
    FAILED
}

/**
 * Replication mode for distributed operations
 */
enum class ReplicationMode {
    SYNC,
    ASYNC,
    MIXED
}

/**
 * Event handler interface
 */
interface EventHandler<T> {
    fun onEvent(event: T, sequence: Long, endOfBatch: Boolean)
}

/**
 * Workflow interface
 */
interface Workflow {
    val id: String
    val name: String
    val source: WorkflowSource
    val sink: WorkflowSink
    val stages: List<WorkflowStage>
}

/**
 * Workflow source interface
 */
interface WorkflowSource {
    val topic: String
    val partitionStrategy: PartitionStrategy
}

/**
 * Workflow sink interface
 */
interface WorkflowSink {
    val topic: String
}

/**
 * Workflow stage interface
 */
interface WorkflowStage {
    val id: String
    val parallelism: Int
    
    fun getHandler(): EventHandler<Any>
}

/**
 * Partition strategy interface
 */
interface PartitionStrategy {
    fun getPartition(key: Any, partitionCount: Int): Int
}

/**
 * Workflow status enumeration
 */
enum class WorkflowStatus {
    CREATED,
    RUNNING,
    PAUSED,
    STOPPED,
    COMPLETED,
    FAILED,
    ERROR
}

/**
 * Request message for network transport
 */
data class Request(
    val id: Long,
    val type: String,
    val data: Any
)

/**
 * Response message for network transport
 */
data class Response(
    val id: Long,
    val result: Any,
    val error: String? = null
)
