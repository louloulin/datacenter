package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.NodeInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分布式负载均衡器测试
 */
class DistributedLoadBalancerTest {
    
    private lateinit var loadBalancer: DistributedLoadBalancer
    private lateinit var testNodes: List<NodeInfo>
    
    @BeforeEach
    fun setUp() {
        loadBalancer = DistributedLoadBalancer()
        testNodes = listOf(
            NodeInfo("node1", "192.168.1.1", 8001),
            NodeInfo("node2", "192.168.1.2", 8002),
            NodeInfo("node3", "192.168.1.3", 8003),
            NodeInfo("node4", "192.168.1.4", 8004)
        )
    }
    
    @Test
    fun `test add and remove nodes`() = runBlocking {
        // 测试添加节点
        testNodes.forEach { node ->
            loadBalancer.addNode(node)
        }
        
        // 验证节点选择
        val selectedNode = loadBalancer.chooseNode("test-key")
        assertNotNull(selectedNode)
        assertTrue(testNodes.contains(selectedNode))
        
        // 测试移除节点
        loadBalancer.removeNode("node1")
        
        // 验证移除后的状态
        val stats = loadBalancer.getClusterLoadStats()
        assertEquals(3, stats.totalNodes)
    }
    
    @Test
    fun `test consistent hash distribution`() = runBlocking {
        // 添加节点
        testNodes.forEach { node ->
            loadBalancer.addNode(node)
        }
        
        loadBalancer.setStrategy(LoadBalanceStrategy.CONSISTENT_HASH)
        
        // 测试一致性哈希分布
        val keyDistribution = mutableMapOf<String, Int>()
        
        repeat(1000) { i ->
            val key = "key-$i"
            val selectedNode = loadBalancer.chooseNode(key)
            if (selectedNode != null) {
                keyDistribution[selectedNode.nodeId] = 
                    keyDistribution.getOrDefault(selectedNode.nodeId, 0) + 1
            }
        }
        
        // 验证分布相对均匀（每个节点至少分配到一些key）
        assertEquals(4, keyDistribution.size)
        keyDistribution.values.forEach { count ->
            assertTrue(count > 0, "Each node should handle some keys")
        }
        
        // 验证相同key总是路由到相同节点
        val node1 = loadBalancer.chooseNode("consistent-key")
        val node2 = loadBalancer.chooseNode("consistent-key")
        assertEquals(node1?.nodeId, node2?.nodeId)
    }
    
    @Test
    fun `test load aware routing`() = runBlocking {
        // 添加节点
        testNodes.forEach { node ->
            loadBalancer.addNode(node)
        }
        
        loadBalancer.setStrategy(LoadBalanceStrategy.CONSISTENT_HASH_WITH_LOAD)
        
        // 设置不同的负载
        loadBalancer.updateNodeLoad("node1", 0.9, 0.8, 100) // 高负载
        loadBalancer.updateNodeLoad("node2", 0.2, 0.3, 10)  // 低负载
        loadBalancer.updateNodeLoad("node3", 0.5, 0.5, 50)  // 中等负载
        loadBalancer.updateNodeLoad("node4", 0.1, 0.2, 5)   // 最低负载
        
        // 测试负载感知路由
        val selections = mutableMapOf<String, Int>()
        
        repeat(100) { i ->
            val selectedNode = loadBalancer.chooseNode("load-test-$i")
            if (selectedNode != null) {
                selections[selectedNode.nodeId] = 
                    selections.getOrDefault(selectedNode.nodeId, 0) + 1
            }
        }
        
        // 验证低负载节点被选择更多
        val node2Selections = selections.getOrDefault("node2", 0)
        val node4Selections = selections.getOrDefault("node4", 0)
        val node1Selections = selections.getOrDefault("node1", 0)
        
        // 低负载节点应该被选择更多次
        assertTrue(node2Selections + node4Selections > node1Selections)
    }
    
    @Test
    fun `test least loaded strategy`() = runBlocking {
        // 添加节点
        testNodes.forEach { node ->
            loadBalancer.addNode(node)
        }
        
        loadBalancer.setStrategy(LoadBalanceStrategy.LEAST_LOADED)
        
        // 设置负载
        loadBalancer.updateNodeLoad("node1", 0.8, 0.7, 80)
        loadBalancer.updateNodeLoad("node2", 0.3, 0.4, 30)
        loadBalancer.updateNodeLoad("node3", 0.6, 0.5, 60)
        loadBalancer.updateNodeLoad("node4", 0.1, 0.2, 10) // 最低负载
        
        // 测试最少负载策略
        repeat(10) {
            val selectedNode = loadBalancer.chooseNode("any-key-$it")
            // 应该总是选择node4（负载最低）
            assertEquals("node4", selectedNode?.nodeId)
        }
    }
    
    @Test
    fun `test round robin strategy`() = runBlocking {
        // 添加节点
        testNodes.forEach { node ->
            loadBalancer.addNode(node)
        }
        
        loadBalancer.setStrategy(LoadBalanceStrategy.ROUND_ROBIN)
        
        // 测试轮询策略
        val selections = mutableListOf<String>()
        
        repeat(12) { // 3轮完整轮询
            val selectedNode = loadBalancer.chooseNode("round-robin-$it")
            if (selectedNode != null) {
                selections.add(selectedNode.nodeId)
            }
        }
        
        // 验证轮询分布
        val nodeSelectionCounts = selections.groupingBy { it }.eachCount()
        
        // 每个节点应该被选择相同次数
        nodeSelectionCounts.values.forEach { count ->
            assertEquals(3, count) // 12次选择 / 4个节点 = 3次每个
        }
    }
    
    @Test
    fun `test cluster load statistics`() = runBlocking {
        // 添加节点
        testNodes.forEach { node ->
            loadBalancer.addNode(node)
        }
        
        // 更新负载信息
        loadBalancer.updateNodeLoad("node1", 0.5, 0.6, 50)
        loadBalancer.updateNodeLoad("node2", 0.3, 0.4, 30)
        loadBalancer.updateNodeLoad("node3", 0.7, 0.8, 70)
        loadBalancer.updateNodeLoad("node4", 0.2, 0.3, 20)
        
        val stats = loadBalancer.getClusterLoadStats()
        
        // 验证统计信息
        assertEquals(4, stats.totalNodes)
        assertEquals(0.425, stats.averageCpuUsage, 0.01) // (0.5+0.3+0.7+0.2)/4
        assertEquals(0.525, stats.averageMemoryUsage, 0.01) // (0.6+0.4+0.8+0.3)/4
        assertEquals(170, stats.totalQueueDepth) // 50+30+70+20
    }
    
    @Test
    fun `test consistent hash ring functionality`() {
        val hashRing = ConsistentHashRing()
        
        // 测试空环
        assertNull(hashRing.getNode("test-key"))
        assertTrue(hashRing.getAllNodes().isEmpty())
        
        // 添加节点
        val node1 = NodeInfo("node1", "host1", 8001)
        val node2 = NodeInfo("node2", "host2", 8002)
        
        hashRing.addNode(node1)
        hashRing.addNode(node2)
        
        // 测试节点选择
        val selectedNode = hashRing.getNode("test-key")
        assertNotNull(selectedNode)
        assertTrue(listOf(node1, node2).any { it.nodeId == selectedNode.nodeId })
        
        // 测试候选节点
        val candidates = hashRing.getCandidateNodes("test-key", 2)
        assertEquals(2, candidates.size)
        
        // 测试获取所有节点
        val allNodes = hashRing.getAllNodes()
        assertEquals(2, allNodes.size)
        
        // 测试移除节点
        hashRing.removeNode("node1")
        val remainingNodes = hashRing.getAllNodes()
        assertEquals(1, remainingNodes.size)
        assertEquals("node2", remainingNodes[0].nodeId)
    }
    
    @Test
    fun `test node load info data class`() {
        val loadInfo = NodeLoadInfo(
            nodeId = "test-node",
            cpuUsage = 0.5,
            memoryUsage = 0.6,
            queueDepth = 100,
            lastUpdateTime = System.currentTimeMillis()
        )
        
        assertEquals("test-node", loadInfo.nodeId)
        assertEquals(0.5, loadInfo.cpuUsage)
        assertEquals(0.6, loadInfo.memoryUsage)
        assertEquals(100, loadInfo.queueDepth)
        assertTrue(loadInfo.lastUpdateTime > 0)
    }
    
    @Test
    fun `test empty cluster behavior`() = runBlocking {
        // 测试空集群的行为
        val selectedNode = loadBalancer.chooseNode("any-key")
        assertNull(selectedNode)
        
        val stats = loadBalancer.getClusterLoadStats()
        assertEquals(0, stats.totalNodes)
        assertEquals(0.0, stats.averageCpuUsage)
        assertEquals(0.0, stats.averageMemoryUsage)
        assertEquals(0, stats.totalQueueDepth)
    }
    
    @Test
    fun `test load balancer strategy switching`() = runBlocking {
        // 添加节点
        testNodes.forEach { node ->
            loadBalancer.addNode(node)
        }
        
        // 测试策略切换
        loadBalancer.setStrategy(LoadBalanceStrategy.CONSISTENT_HASH)
        val node1 = loadBalancer.chooseNode("test-key")
        
        loadBalancer.setStrategy(LoadBalanceStrategy.ROUND_ROBIN)
        val node2 = loadBalancer.chooseNode("test-key")
        
        // 不同策略可能选择不同节点
        assertNotNull(node1)
        assertNotNull(node2)
    }
}