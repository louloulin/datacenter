package com.hftdc.disruptorx

import com.hftdc.disruptorx.api.DistributedEventBus
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.api.WorkflowManager
import com.hftdc.disruptorx.core.WorkflowManagerConfig
import com.hftdc.disruptorx.core.WorkflowManagerImpl
import com.hftdc.disruptorx.distributed.DefaultSequenceBroadcaster
import com.hftdc.disruptorx.distributed.DistributedEventBusConfig
import com.hftdc.disruptorx.distributed.DistributedEventBusImpl
import com.hftdc.disruptorx.distributed.NetworkConfig
import com.hftdc.disruptorx.distributed.NetworkNodeConfig
import com.hftdc.disruptorx.distributed.NodeManagerConfig
import com.hftdc.disruptorx.distributed.NodeManagerImpl
import com.hftdc.disruptorx.distributed.SequenceBroadcaster
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * DisruptorX主类
 * 提供创建和配置DisruptorX实例的工厂方法
 */
object DisruptorX {
    
    /**
     * 创建DisruptorX节点
     * @param config 节点配置
     * @return DisruptorXNode实例
     */
    fun createNode(config: DisruptorXConfig): DisruptorXNode {
        // 创建节点管理器
        val nodeManager = NodeManagerImpl(
            localNodeId = config.nodeId,
            localNodeRole = config.nodeRole,
            config = NodeManagerConfig(
                networkConfig = NetworkNodeConfig(
                    host = config.host,
                    port = config.port
                ),
                heartbeatInterval = config.heartbeatIntervalMillis,
                nodeTimeoutInterval = config.nodeTimeoutIntervalMillis,
                membershipCheckInterval = config.membershipCheckIntervalMillis
            )
        )
        
        // 创建序列广播器
        val sequenceBroadcaster = DefaultSequenceBroadcaster(nodeManager)
        
        // 创建分布式事件总线
        val eventBus = DistributedEventBusImpl(
            nodeManager = nodeManager,
            localNodeId = config.nodeId,
            config = DistributedEventBusConfig(
                networkConfig = NetworkConfig(
                    port = config.port,
                    connectionTimeout = TimeUnit.SECONDS.toMillis(5),
                    eventBatchSize = config.eventBatchSize,
                    eventBatchTimeWindowMs = config.eventBatchTimeWindowMillis
                )
            )
        )
        
        // 创建工作流管理器
        val workflowManager = WorkflowManagerImpl(
            eventBus = eventBus,
            config = WorkflowManagerConfig(
                maxConcurrentWorkflows = config.maxConcurrentWorkflows,
                defaultExecutorThreads = config.defaultExecutorThreads
            )
        )
        
        return DisruptorXNodeImpl(
            nodeManager = nodeManager,
            eventBus = eventBus,
            workflowManager = workflowManager,
            sequenceBroadcaster = sequenceBroadcaster
        )
    }
    
    /**
     * 创建带默认配置的DisruptorX节点
     * @param host 主机地址
     * @param port 端口
     * @return DisruptorXNode实例
     */
    fun createNode(host: String, port: Int): DisruptorXNode {
        return createNode(DisruptorXConfig(
            nodeId = generateNodeId(),
            host = host,
            port = port
        ))
    }
    
    /**
     * 生成唯一节点ID
     * @return 节点ID
     */
    private fun generateNodeId(): String {
        return "node-" + UUID.randomUUID().toString().substring(0, 8)
    }
}

/**
 * DisruptorX节点接口
 * 表示一个完整的DisruptorX实例
 */
interface DisruptorXNode {
    /**
     * 分布式事件总线
     */
    val eventBus: DistributedEventBus
    
    /**
     * 工作流管理器
     */
    val workflowManager: WorkflowManager
    
    /**
     * 初始化节点
     */
    fun initialize()
    
    /**
     * 加入集群
     * @param seedNodes 种子节点列表，格式为"host1:port1,host2:port2"
     */
    fun joinCluster(seedNodes: String)
    
    /**
     * 离开集群
     */
    fun leaveCluster()
    
    /**
     * 关闭节点
     */
    fun shutdown()
}

/**
 * DisruptorX节点实现
 */
internal class DisruptorXNodeImpl(
    private val nodeManager: NodeManagerImpl,
    override val eventBus: DistributedEventBus,
    override val workflowManager: WorkflowManager,
    private val sequenceBroadcaster: SequenceBroadcaster
) : DisruptorXNode {
    
    /**
     * 初始化节点
     */
    override fun initialize() {
        try {
            nodeManager.initialize()
            
            runBlocking {
                (eventBus as DistributedEventBusImpl).initialize()
            }
        } catch (e: Exception) {
            println("Error initializing DisruptorX node: ${e.message}")
            throw e
        }
    }
    
    /**
     * 加入集群
     * @param seedNodes 种子节点列表
     */
    override fun joinCluster(seedNodes: String) {
        nodeManager.join(seedNodes)
    }
    
    /**
     * 离开集群
     */
    override fun leaveCluster() {
        nodeManager.leave()
    }
    
    /**
     * 关闭节点
     */
    override fun shutdown() {
        runBlocking {
            (eventBus as DistributedEventBusImpl).shutdown()
        }
        nodeManager.shutdown()
    }
}

/**
 * DisruptorX配置
 */
data class DisruptorXConfig(
    val nodeId: String = "node-" + UUID.randomUUID().toString().substring(0, 8),
    val host: String,
    val port: Int,
    val nodeRole: NodeRole = NodeRole.MIXED,
    val heartbeatIntervalMillis: Long = 500,
    val nodeTimeoutIntervalMillis: Long = 2000,
    val membershipCheckIntervalMillis: Long = 1000,
    val eventBatchSize: Int = 100,
    val eventBatchTimeWindowMillis: Long = 10,
    val maxConcurrentWorkflows: Int = 100,
    val defaultExecutorThreads: Int = Runtime.getRuntime().availableProcessors()
)