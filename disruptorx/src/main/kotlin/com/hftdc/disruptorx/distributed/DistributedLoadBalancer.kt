package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.NodeInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 分布式负载均衡器
 * 使用一致性哈希和负载感知路由
 */
class DistributedLoadBalancer {
    
    // 一致性哈希环
    private val hashRing = ConsistentHashRing()
    
    // 节点负载信息
    private val nodeLoads = ConcurrentHashMap<String, NodeLoadInfo>()
    
    // 负载更新互斥锁
    private val loadUpdateMutex = Mutex()
    
    // 负载均衡策略
    private var strategy: LoadBalanceStrategy = LoadBalanceStrategy.CONSISTENT_HASH_WITH_LOAD
    
    /**
     * 添加节点到负载均衡器
     */
    suspend fun addNode(nodeInfo: NodeInfo) {
        hashRing.addNode(nodeInfo)
        
        loadUpdateMutex.withLock {
            nodeLoads[nodeInfo.nodeId] = NodeLoadInfo(
                nodeId = nodeInfo.nodeId,
                cpuUsage = 0.0,
                memoryUsage = 0.0,
                queueDepth = 0,
                lastUpdateTime = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * 从负载均衡器移除节点
     */
    suspend fun removeNode(nodeId: String) {
        hashRing.removeNode(nodeId)
        
        loadUpdateMutex.withLock {
            nodeLoads.remove(nodeId)
        }
    }
    
    /**
     * 选择最优节点
     * 基于负载分数选择节点
     */
    suspend fun chooseNode(key: String): NodeInfo? {
        return when (strategy) {
            LoadBalanceStrategy.CONSISTENT_HASH -> {
                hashRing.getNode(key)
            }
            LoadBalanceStrategy.CONSISTENT_HASH_WITH_LOAD -> {
                chooseNodeWithLoadAware(key)
            }
            LoadBalanceStrategy.LEAST_LOADED -> {
                chooseLeastLoadedNode()
            }
            LoadBalanceStrategy.ROUND_ROBIN -> {
                chooseRoundRobinNode()
            }
        }
    }
    
    /**
     * 更新节点负载信息
     */
    suspend fun updateNodeLoad(
        nodeId: String,
        cpuUsage: Double,
        memoryUsage: Double,
        queueDepth: Int
    ) {
        loadUpdateMutex.withLock {
            nodeLoads[nodeId] = NodeLoadInfo(
                nodeId = nodeId,
                cpuUsage = cpuUsage,
                memoryUsage = memoryUsage,
                queueDepth = queueDepth,
                lastUpdateTime = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * 获取节点负载分数
     * 分数越低表示负载越轻
     */
    private fun calculateLoadScore(nodeId: String): Double {
        val loadInfo = nodeLoads[nodeId] ?: return Double.MAX_VALUE
        
        // 检查负载信息是否过期（超过30秒）
        if (System.currentTimeMillis() - loadInfo.lastUpdateTime > 30000) {
            return Double.MAX_VALUE
        }
        
        // 计算综合负载分数
        val cpuWeight = 0.4
        val memoryWeight = 0.3
        val queueWeight = 0.3
        
        return (loadInfo.cpuUsage * cpuWeight +
                loadInfo.memoryUsage * memoryWeight +
                (loadInfo.queueDepth / 1000.0) * queueWeight)
    }
    
    /**
     * 基于负载感知的一致性哈希选择
     */
    private suspend fun chooseNodeWithLoadAware(key: String): NodeInfo? {
        // 获取一致性哈希的候选节点（包括虚拟节点）
        val candidates = hashRing.getCandidateNodes(key, 3)
        
        if (candidates.isEmpty()) return null
        
        // 选择负载最轻的节点
        return candidates.minByOrNull { calculateLoadScore(it.nodeId) }
    }
    
    /**
     * 选择负载最轻的节点
     */
    private fun chooseLeastLoadedNode(): NodeInfo? {
        val availableNodes = hashRing.getAllNodes()
        if (availableNodes.isEmpty()) return null
        
        return availableNodes.minByOrNull { calculateLoadScore(it.nodeId) }
    }
    
    /**
     * 轮询选择节点
     */
    private val roundRobinCounter = AtomicLong(0)
    
    private fun chooseRoundRobinNode(): NodeInfo? {
        val availableNodes = hashRing.getAllNodes()
        if (availableNodes.isEmpty()) return null
        
        val index = (roundRobinCounter.getAndIncrement() % availableNodes.size).toInt()
        return availableNodes[index]
    }
    
    /**
     * 设置负载均衡策略
     */
    fun setStrategy(strategy: LoadBalanceStrategy) {
        this.strategy = strategy
    }
    
    /**
     * 获取集群负载统计
     */
    fun getClusterLoadStats(): ClusterLoadStats {
        val loads = nodeLoads.values.toList()
        
        if (loads.isEmpty()) {
            return ClusterLoadStats(
                totalNodes = 0,
                averageCpuUsage = 0.0,
                averageMemoryUsage = 0.0,
                totalQueueDepth = 0
            )
        }
        
        return ClusterLoadStats(
            totalNodes = loads.size,
            averageCpuUsage = loads.map { it.cpuUsage }.average(),
            averageMemoryUsage = loads.map { it.memoryUsage }.average(),
            totalQueueDepth = loads.sumOf { it.queueDepth }
        )
    }
}

/**
 * 一致性哈希环实现
 */
class ConsistentHashRing {
    private val ring = mutableMapOf<Long, NodeInfo>()
    private val nodeToHashes = mutableMapOf<String, MutableList<Long>>()
    private val virtualNodeCount = 150 // 每个物理节点对应的虚拟节点数
    
    /**
     * 添加节点
     */
    fun addNode(nodeInfo: NodeInfo) {
        val hashes = mutableListOf<Long>()
        
        // 为每个物理节点创建多个虚拟节点
        repeat(virtualNodeCount) { i ->
            val virtualNodeKey = "${nodeInfo.nodeId}:$i"
            val hash = hash(virtualNodeKey)
            ring[hash] = nodeInfo
            hashes.add(hash)
        }
        
        nodeToHashes[nodeInfo.nodeId] = hashes
    }
    
    /**
     * 移除节点
     */
    fun removeNode(nodeId: String) {
        val hashes = nodeToHashes.remove(nodeId) ?: return
        hashes.forEach { ring.remove(it) }
    }
    
    /**
     * 获取节点
     */
    fun getNode(key: String): NodeInfo? {
        if (ring.isEmpty()) return null
        
        val hash = hash(key)
        val sortedHashes = ring.keys.sorted()
        
        // 找到第一个大于等于key hash的节点
        val targetHash = sortedHashes.find { it >= hash } ?: sortedHashes.first()
        
        return ring[targetHash]
    }
    
    /**
     * 获取候选节点列表
     */
    fun getCandidateNodes(key: String, count: Int): List<NodeInfo> {
        if (ring.isEmpty()) return emptyList()
        
        val hash = hash(key)
        val sortedHashes = ring.keys.sorted()
        val candidates = mutableSetOf<NodeInfo>()
        
        // 从hash位置开始，顺时针查找不同的物理节点
        var startIndex = sortedHashes.indexOfFirst { it >= hash }
        if (startIndex == -1) startIndex = 0
        
        var currentIndex = startIndex
        while (candidates.size < count && candidates.size < getAllNodes().size) {
            val nodeInfo = ring[sortedHashes[currentIndex]]
            if (nodeInfo != null) {
                candidates.add(nodeInfo)
            }
            currentIndex = (currentIndex + 1) % sortedHashes.size
            
            // 避免无限循环
            if (currentIndex == startIndex && candidates.isNotEmpty()) break
        }
        
        return candidates.toList()
    }
    
    /**
     * 获取所有节点
     */
    fun getAllNodes(): List<NodeInfo> {
        return ring.values.distinctBy { it.nodeId }
    }
    
    /**
     * 哈希函数
     */
    private fun hash(key: String): Long {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(key.toByteArray())
        
        return ((digest[3].toLong() and 0xFF) shl 24) or
               ((digest[2].toLong() and 0xFF) shl 16) or
               ((digest[1].toLong() and 0xFF) shl 8) or
               (digest[0].toLong() and 0xFF)
    }
}

/**
 * 负载均衡策略
 */
enum class LoadBalanceStrategy {
    CONSISTENT_HASH,           // 纯一致性哈希
    CONSISTENT_HASH_WITH_LOAD, // 一致性哈希 + 负载感知
    LEAST_LOADED,              // 最少负载
    ROUND_ROBIN                // 轮询
}

/**
 * 节点负载信息
 */
data class NodeLoadInfo(
    val nodeId: String,
    val cpuUsage: Double,      // CPU使用率 (0.0-1.0)
    val memoryUsage: Double,   // 内存使用率 (0.0-1.0)
    val queueDepth: Int,       // 队列深度
    val lastUpdateTime: Long   // 最后更新时间
)

/**
 * 集群负载统计
 */
data class ClusterLoadStats(
    val totalNodes: Int,
    val averageCpuUsage: Double,
    val averageMemoryUsage: Double,
    val totalQueueDepth: Int
)