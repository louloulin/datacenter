package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.DistributedEventBus
import com.hftdc.disruptorx.api.NodeInfo
import com.hftdc.disruptorx.api.NodeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * 分布式事件总线实现
 *
 * @property nodeManager 节点管理器
 * @property localNodeId 本地节点ID
 * @property config 配置信息
 */
class DistributedEventBusImpl(
    private val nodeManager: NodeManager,
    private val localNodeId: String,
    private val config: DistributedEventBusConfig
) : DistributedEventBus, CoroutineScope {
    
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Default + job
    
    // 本地处理程序注册表 <主题, 处理程序列表>
    private val localHandlers = ConcurrentHashMap<String, MutableList<suspend (Any) -> Unit>>()
    
    // 主题路由缓存 <主题, 节点ID>
    private val topicRoutingCache = ConcurrentHashMap<String, String>()
    
    // 路由缓存互斥锁
    private val routingCacheMutex = Mutex()
    
    // 网络通信客户端
    private val networkClient = NettyEventTransport(config.networkConfig, localNodeId)
    
    /**
     * 初始化
     */
    suspend fun initialize() {
        // 初始化网络通信客户端
        networkClient.initialize()
        
        // 注册事件接收处理器
        networkClient.setEventReceiver { event, topic ->
            processReceivedEvent(event, topic)
        }
    }
    
    /**
     * 关闭事件总线
     */
    suspend fun shutdown() {
        networkClient.shutdown()
        job.cancel()
    }

    /**
     * 发布事件
     * @param event 要发布的事件
     * @param topic 目标主题
     */
    override suspend fun publish(event: Any, topic: String) {
        println("Publishing event: $event to topic: $topic")
        println("Local handlers: ${localHandlers.keys}")
        
        // 检查是否有本地订阅者
        val hasLocalSubscribers = localHandlers.containsKey(topic) && 
                                 localHandlers[topic]?.isNotEmpty() == true
        
        println("Has local subscribers: $hasLocalSubscribers")
        
        if (hasLocalSubscribers) {
            // 直接本地处理
            println("Processing locally")
            processReceivedEvent(event, topic)
        } else {
            // 确定目标节点
            val targetNodeId = determineTargetNode(topic)
            println("Target node ID: $targetNodeId, local node ID: $localNodeId")
            
            if (targetNodeId == localNodeId) {
                // 本地发布
                println("Processing as local node")
                processReceivedEvent(event, topic)
            } else {
                // 远程发布
                val clusterMembers = nodeManager.getClusterMembers()
                val node = clusterMembers.find { it.nodeId == targetNodeId }
                if (node == null) {
                    // 节点不可用，回退到本地处理
                    println("Node not found, falling back to local processing")
                    processReceivedEvent(event, topic)
                    return
                }
                
                // 发送到远程节点
                println("Sending to remote node: ${node.nodeId}")
                networkClient.sendEvent(event, topic, node)
            }
        }
    }

    /**
     * 订阅主题
     * @param topic 要订阅的主题
     * @param handler 事件处理函数
     */
    override fun subscribe(topic: String, handler: suspend (Any) -> Unit) {
        println("Subscribing to topic: $topic")
        val handlers = localHandlers.computeIfAbsent(topic) { mutableListOf() }
        synchronized(handlers) {
            handlers.add(handler)
        }
        println("Handlers for topic $topic: ${handlers.size}")
        
        // 通知集群有节点对该主题感兴趣
        launch {
            networkClient.registerTopicInterest(topic)
        }
    }

    /**
     * 取消订阅
     * @param topic 主题名称
     * @param handler 要取消的处理函数
     */
    override fun unsubscribe(topic: String, handler: suspend (Any) -> Unit) {
        val handlers = localHandlers[topic] ?: return
        synchronized(handlers) {
            handlers.remove(handler)
            if (handlers.isEmpty()) {
                localHandlers.remove(topic)
                
                // 通知集群该节点不再对该主题感兴趣
                launch {
                    networkClient.unregisterTopicInterest(topic)
                }
            }
        }
    }
    
    /**
     * 处理接收到的事件
     * @param event 事件对象
     * @param topic 主题
     */
    private fun processReceivedEvent(event: Any, topic: String) {
        val handlers = localHandlers[topic]
        println("Processing event for topic: $topic, handlers count: ${handlers?.size ?: 0}")
        
        if (handlers == null || handlers.isEmpty()) {
            println("No handlers found for topic: $topic")
            return
        }
        
        // 为每个处理器异步调用
        handlers.forEach { handler ->
            launch {
                try {
                    println("Calling handler for event: $event")
                    handler(event)
                } catch (e: Exception) {
                    // 记录错误但不影响其他处理器
                    println("Error processing event: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
    
    /**
     * 确定事件应该发送到哪个节点
     * @param topic 主题
     * @return 目标节点ID
     */
    private suspend fun determineTargetNode(topic: String): String {
        // 先查询缓存
        topicRoutingCache[topic]?.let { return it }
        
        return routingCacheMutex.withLock {
            // 双重检查，可能在获取锁过程中已被其他线程更新
            topicRoutingCache[topic]?.let { return@withLock it }
            
            // 查找订阅了该主题的节点
            val interestedNodes = networkClient.getNodesInterestedInTopic(topic)
            
            val targetNodeId = if (interestedNodes.isEmpty()) {
                // 没有节点对此主题感兴趣，使用一致性哈希选择节点
                val nodes = nodeManager.getClusterMembers()
                if (nodes.isEmpty() || nodes.size == 1) {
                    localNodeId // 单节点模式或无其他节点，本地处理
                } else {
                    val hashCode = topic.hashCode()
                    val index = Math.abs(hashCode % nodes.size)
                    nodes[index].nodeId
                }
            } else {
                // 有节点对此主题感兴趣，优先选择本地节点
                if (interestedNodes.contains(localNodeId)) {
                    localNodeId
                } else {
                    interestedNodes.first()
                }
            }
            
            // 缓存路由结果
            topicRoutingCache[topic] = targetNodeId
            targetNodeId
        }
    }
}

/**
 * 分布式事件总线配置
 */
data class DistributedEventBusConfig(
    val networkConfig: NetworkConfig
)

/**
 * 网络配置
 */
data class NetworkConfig(
    val port: Int,
    val connectionTimeout: Long = 5000,
    val eventBatchSize: Int = 100,
    val eventBatchTimeWindowMs: Long = 10
)

/**
 * Netty事件传输实现
 * 注意：这是一个简化的实现，实际应用中应该有更完整的实现
 */
class NettyEventTransport(
    private val config: NetworkConfig,
    private val localNodeId: String
) {
    // 事件接收回调
    private var eventReceiver: (suspend (Any, String) -> Unit)? = null
    
    // 主题兴趣注册表 <主题, 节点ID列表>
    private val topicInterests = ConcurrentHashMap<String, MutableSet<String>>()
    
    /**
     * 初始化网络传输
     */
    suspend fun initialize() {
        // 实际实现应该包括：
        // 1. 初始化Netty服务器和客户端
        // 2. 设置编解码器和处理器
        // 3. 启动服务器监听连接
        // 此处为简化实现
    }
    
    /**
     * 关闭网络传输
     */
    suspend fun shutdown() {
        // 实际实现应该包括：
        // 1. 关闭所有客户端连接
        // 2. 关闭服务器
        // 此处为简化实现
    }
    
    /**
     * 设置事件接收器
     * @param receiver 接收回调
     */
    fun setEventReceiver(receiver: suspend (Any, String) -> Unit) {
        this.eventReceiver = receiver
    }
    
    /**
     * 发送事件到远程节点
     * @param event 事件对象
     * @param topic 主题
     * @param targetNode 目标节点
     */
    suspend fun sendEvent(event: Any, topic: String, targetNode: NodeInfo) {
        // 检查是否为本地节点
        if (targetNode.nodeId == localNodeId) {
            // 本地处理
            eventReceiver?.invoke(event, topic)
        } else {
            // 实际网络传输实现
            // 目前简化为本地处理（在真实环境中应该通过网络发送）
            eventReceiver?.invoke(event, topic)
        }
    }
    
    /**
     * 注册主题兴趣
     * @param topic 主题
     */
    suspend fun registerTopicInterest(topic: String) {
        val interestedNodes = topicInterests.computeIfAbsent(topic) { ConcurrentHashMap.newKeySet() }
        interestedNodes.add(localNodeId)
        
        // 实际实现应该包括通知其他节点该节点对此主题感兴趣
        // 目前为单节点模式，无需网络通信
    }
    
    /**
     * 取消注册主题兴趣
     * @param topic 主题
     */
    suspend fun unregisterTopicInterest(topic: String) {
        val interestedNodes = topicInterests[topic] ?: return
        interestedNodes.remove(localNodeId)
        if (interestedNodes.isEmpty()) {
            topicInterests.remove(topic)
        }
        
        // 实际实现应该包括通知其他节点该节点不再对此主题感兴趣
    }
    
    /**
     * 获取对指定主题感兴趣的节点列表
     * @param topic 主题
     * @return 节点ID列表
     */
    suspend fun getNodesInterestedInTopic(topic: String): List<String> {
        return topicInterests[topic]?.toList() ?: emptyList()
    }
}