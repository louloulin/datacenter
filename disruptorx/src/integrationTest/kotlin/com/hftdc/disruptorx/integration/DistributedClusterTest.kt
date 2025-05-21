package com.hftdc.disruptorx.integration

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.api.NodeInfo
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.api.NodeStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DistributedClusterTest {

    companion object {
        // 测试节点配置
        private const val NODE1_HOST = "localhost"
        private const val NODE1_PORT = 19091
        private const val NODE2_HOST = "localhost"
        private const val NODE2_PORT = 19092
        private const val NODE3_HOST = "localhost"
        private const val NODE3_PORT = 19093
    }
    
    // 测试节点
    private lateinit var node1: TestNode
    private lateinit var node2: TestNode
    private lateinit var node3: TestNode
    
    @BeforeAll
    fun setup() {
        // 创建测试节点
        node1 = createTestNode("node1", NODE1_HOST, NODE1_PORT)
        node2 = createTestNode("node2", NODE2_HOST, NODE2_PORT)
        node3 = createTestNode("node3", NODE3_HOST, NODE3_PORT)
        
        // 启动节点
        node1.start()
        node2.start()
        node3.start()
        
        // 等待集群形成
        runBlocking { delay(5000) }
    }
    
    @AfterAll
    fun tearDown() {
        // 关闭节点
        node3.shutdown()
        node2.shutdown()
        node1.shutdown()
    }
    
    @Test
    @Timeout(30)
    fun `should form a cluster with all nodes`() {
        // 验证集群成员
        val members1 = node1.node.nodeManager.getClusterMembers()
        val members2 = node2.node.nodeManager.getClusterMembers()
        val members3 = node3.node.nodeManager.getClusterMembers()
        
        // 验证每个节点都看到3个成员
        assertEquals(3, members1.size, "节点1应该看到3个成员")
        assertEquals(3, members2.size, "节点2应该看到3个成员")
        assertEquals(3, members3.size, "节点3应该看到3个成员")
        
        // 验证所有节点都是活跃的
        members1.forEach { 
            assertEquals(NodeStatus.ACTIVE, it.status, "所有节点应该是活跃的") 
        }
    }
    
    @Test
    @Timeout(30)
    fun `should elect a leader`() {
        // 验证领导选举
        val leader1 = node1.node.nodeManager.getLeader()
        val leader2 = node2.node.nodeManager.getLeader()
        val leader3 = node3.node.nodeManager.getLeader()
        
        // 验证所有节点看到相同的领导节点
        assertNotNull(leader1, "节点1应该看到领导")
        assertNotNull(leader2, "节点2应该看到领导")
        assertNotNull(leader3, "节点3应该看到领导")
        
        // 验证领导ID一致
        assertEquals(leader1.nodeId, leader2.nodeId, "节点1和节点2看到的领导应该一致")
        assertEquals(leader2.nodeId, leader3.nodeId, "节点2和节点3看到的领导应该一致")
        
        println("选举出的领导节点: ${leader1.nodeId}")
    }
    
    @Test
    @Timeout(60)
    fun `should distribute events across the cluster`() = runBlocking {
        // 创建测试主题
        val testTopic = "distributed-test-topic"
        
        // 创建接收计数器和锁存器
        val receivedCounts = mapOf(
            "node1" to AtomicInteger(0),
            "node2" to AtomicInteger(0),
            "node3" to AtomicInteger(0)
        )
        val latch = CountDownLatch(30) // 预期接收30个事件 (10个事件 x 3个节点)
        
        // 在所有节点上订阅测试主题
        node1.node.eventBus.subscribe(testTopic) { event ->
            println("节点1收到事件: $event")
            receivedCounts["node1"]!!.incrementAndGet()
            latch.countDown()
        }
        
        node2.node.eventBus.subscribe(testTopic) { event ->
            println("节点2收到事件: $event")
            receivedCounts["node2"]!!.incrementAndGet()
            latch.countDown()
        }
        
        node3.node.eventBus.subscribe(testTopic) { event ->
            println("节点3收到事件: $event")
            receivedCounts["node3"]!!.incrementAndGet()
            latch.countDown()
        }
        
        // 等待订阅就绪
        delay(1000)
        
        // 从节点1发布10个事件
        for (i in 1..10) {
            val event = "TestEvent-$i"
            println("节点1发布事件: $event")
            node1.node.eventBus.publish(event, testTopic)
            delay(100) // 稍微延迟，避免消息合并
        }
        
        // 等待事件处理完成
        assertTrue(latch.await(30, TimeUnit.SECONDS), "等待事件处理超时")
        
        // 验证每个节点都收到了至少一些事件
        assertTrue(receivedCounts["node1"]!!.get() > 0, "节点1应该收到事件")
        assertTrue(receivedCounts["node2"]!!.get() > 0, "节点2应该收到事件")
        assertTrue(receivedCounts["node3"]!!.get() > 0, "节点3应该收到事件")
        
        // 验证总接收量等于预期
        val totalReceived = receivedCounts.values.sumOf { it.get() }
        assertEquals(30, totalReceived, "所有节点应该总共收到30个事件")
        
        println("事件分布情况:")
        receivedCounts.forEach { (nodeId, count) ->
            println("$nodeId: ${count.get()}个事件")
        }
    }
    
    /**
     * 创建测试节点
     */
    private fun createTestNode(nodeId: String, host: String, port: Int): TestNode {
        val config = DisruptorXConfig(
            nodeId = nodeId,
            host = host,
            port = port,
            seedNodes = listOf(
                "$NODE1_HOST:$NODE1_PORT",
                "$NODE2_HOST:$NODE2_PORT",
                "$NODE3_HOST:$NODE3_PORT"
            )
        )
        return TestNode(nodeId, DisruptorX.createNode(config))
    }
    
    /**
     * 测试节点封装
     */
    class TestNode(val id: String, val node: com.hftdc.disruptorx.DisruptorXNode) {
        fun start() {
            println("启动节点: $id")
            node.initialize()
        }
        
        fun shutdown() {
            println("关闭节点: $id")
            node.shutdown()
        }
    }
} 