package com.hftdc.disruptorx.integration

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.DisruptorXNode
import com.hftdc.disruptorx.api.Workflow
import com.hftdc.disruptorx.dsl.workflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FaultToleranceTest {

    // 测试节点
    private val nodes = ConcurrentHashMap<String, DisruptorXNode>()
    
    // 故障注入器
    private lateinit var faultInjector: FaultInjector
    
    // 测试数据存储
    private val receivedEvents = ConcurrentHashMap<String, AtomicInteger>()
    private val processedEvents = ConcurrentHashMap<String, AtomicInteger>()
    
    // 测试状态
    private var initialLeader = ""
    private val testCompleted = AtomicBoolean(false)
    
    @BeforeAll
    fun setup() {
        // 创建测试节点
        nodes["node1"] = createNode("node1", "localhost", 29091)
        nodes["node2"] = createNode("node2", "localhost", 29092)
        nodes["node3"] = createNode("node3", "localhost", 29093)
        
        // 初始化故障注入器
        faultInjector = FaultInjector().registerNodes(nodes)
        
        // 初始化计数器
        nodes.keys.forEach { nodeId ->
            receivedEvents[nodeId] = AtomicInteger(0)
            processedEvents[nodeId] = AtomicInteger(0)
        }
        
        // 启动所有节点
        nodes.forEach { (nodeId, node) ->
            println("启动节点: $nodeId")
            node.initialize()
        }
        
        // 等待集群形成
        runBlocking { delay(5000) }
        
        // 记录初始领导节点
        initialLeader = findLeaderNodeId()
        println("初始领导节点: $initialLeader")
    }
    
    @AfterAll
    fun tearDown() {
        // 关闭所有节点
        nodes.forEach { (nodeId, node) ->
            println("关闭节点: $nodeId")
            node.shutdown()
        }
    }
    
    @Test
    @Timeout(120)
    fun `should recover from leader node failure`() = runBlocking {
        // 创建工作流
        val workflowId = "fault-tolerance-workflow"
        nodes.forEach { (nodeId, node) ->
            val workflow = createTestWorkflow(workflowId, nodeId)
            node.workflowManager.register(workflow)
            node.workflowManager.start(workflowId)
        }
        
        // 等待工作流启动
        delay(2000)
        
        // 准备事件接收机制
        val inputTopic = "input-events"
        val outputTopic = "output-events"
        val totalEvents = 100
        val allEventsLatch = CountDownLatch(totalEvents)
        
        // 在所有节点上订阅输出主题
        nodes.forEach { (nodeId, node) ->
            node.eventBus.subscribe(outputTopic) { event ->
                println("节点 $nodeId 收到处理后的事件: $event")
                processedEvents[nodeId]?.incrementAndGet()
                allEventsLatch.countDown()
            }
        }
        
        // 使用独立线程发布事件
        val publishThread = Thread {
            try {
                runBlocking {
                    // 从非领导节点发布事件
                    val publisherNodeId = nodes.keys.first { it != initialLeader }
                    val publisherNode = nodes[publisherNodeId]!!
                    
                    for (i in 1..totalEvents) {
                        // 找到当前活跃的领导节点
                        val currentLeader = findLeaderNodeId()
                        
                        if (i == 30) {
                            // 在发布30个事件后，杀死领导节点
                            println("在发布30个事件后杀死领导节点: $initialLeader")
                            faultInjector.killNode(initialLeader, 8000)
                            
                            // 等待新的领导节点选举完成
                            delay(10000)
                            val newLeader = findLeaderNodeId()
                            println("新的领导节点: $newLeader")
                            assertNotEquals(initialLeader, newLeader, "新的领导节点应该与初始领导节点不同")
                        }
                        
                        if (i == 60) {
                            // 在发布60个事件后，注入网络延迟
                            val targetNode = nodes.keys.first { it != initialLeader }
                            println("在发布60个事件后注入网络延迟到节点: $targetNode")
                            faultInjector.slowDownNetwork(targetNode, 200, 5000)
                        }
                        
                        // 发布事件
                        val event = "Event-$i"
                        println("发布事件: $event 到节点 $publisherNodeId")
                        publisherNode.eventBus.publish(event, inputTopic)
                        
                        // 适当延迟，避免消息拥塞
                        delay(50)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        publishThread.start()
        
        // 等待所有事件处理完成
        val allEventsProcessed = allEventsLatch.await(90, TimeUnit.SECONDS)
        testCompleted.set(true)
        
        // 验证结果
        assertTrue(allEventsProcessed, "所有事件应在超时前处理完成")
        
        // 打印统计信息
        println("=== 事件处理统计 ===")
        processedEvents.forEach { (nodeId, count) ->
            println("节点 $nodeId 处理的事件数: ${count.get()}")
        }
        
        // 验证所有事件都被处理
        val totalProcessed = processedEvents.values.sumOf { it.get() }
        assertEquals(totalEvents, totalProcessed, "应处理所有发布的事件")
        
        // 等待发布线程完成
        publishThread.join(5000)
    }
    
    @Test
    @Timeout(120)
    fun `should handle network partition and recovery`() = runBlocking {
        // 创建工作流
        val workflowId = "network-partition-workflow"
        nodes.forEach { (nodeId, node) ->
            val workflow = createTestWorkflow(workflowId, nodeId)
            node.workflowManager.register(workflow)
            node.workflowManager.start(workflowId)
        }
        
        // 等待工作流启动
        delay(2000)
        
        // 准备事件接收机制
        val inputTopic = "partition-input-events"
        val outputTopic = "partition-output-events"
        val totalEvents = 100
        val allEventsLatch = CountDownLatch(totalEvents)
        
        // 重置计数器
        nodes.keys.forEach { nodeId ->
            receivedEvents[nodeId]?.set(0)
            processedEvents[nodeId]?.set(0)
        }
        
        // 在所有节点上订阅输出主题
        nodes.forEach { (nodeId, node) ->
            node.eventBus.subscribe(outputTopic) { event ->
                println("节点 $nodeId 收到处理后的事件: $event")
                processedEvents[nodeId]?.incrementAndGet()
                allEventsLatch.countDown()
            }
        }
        
        // 选择两个节点形成一边的网络分区
        val partitionGroup1 = nodes.keys.filter { it != initialLeader }.take(1)
        val partitionGroup2 = nodes.keys.filter { !partitionGroup1.contains(it) }
        
        println("网络分区组1: $partitionGroup1")
        println("网络分区组2: $partitionGroup2")
        
        // 使用独立线程发布事件
        val publishThread = Thread {
            try {
                runBlocking {
                    // 从组1中选择一个节点作为发布者
                    val publisherNodeId = partitionGroup1.first()
                    val publisherNode = nodes[publisherNodeId]!!
                    
                    for (i in 1..totalEvents) {
                        // 模拟网络分区
                        if (i == 30) {
                            println("在发布30个事件后，创建网络分区")
                            
                            // 断开两个分区组之间的连接
                            for (nodeId1 in partitionGroup1) {
                                for (nodeId2 in partitionGroup2) {
                                    // 注入节点间的网络断开
                                    println("断开节点 $nodeId1 和 $nodeId2 之间的连接")
                                    faultInjector.disconnectNetwork(nodeId1, 10000)
                                }
                            }
                            
                            delay(2000) // 等待分区生效
                        }
                        
                        // 在分区期间继续发布事件
                        if (i == 60) {
                            println("在发布60个事件后，修复网络分区")
                            // 不需要显式操作，faultInjector会在指定时间后自动恢复连接
                            delay(2000) // 等待连接恢复
                        }
                        
                        // 发布事件
                        val event = "PartitionEvent-$i"
                        println("发布事件: $event 到节点 $publisherNodeId")
                        publisherNode.eventBus.publish(event, inputTopic)
                        
                        // 适当延迟
                        delay(50)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        publishThread.start()
        
        // 等待所有事件处理完成
        val allEventsProcessed = allEventsLatch.await(90, TimeUnit.SECONDS)
        
        // 验证结果
        assertTrue(allEventsProcessed, "所有事件应在超时前处理完成")
        
        // 打印统计信息
        println("=== 网络分区测试事件处理统计 ===")
        processedEvents.forEach { (nodeId, count) ->
            println("节点 $nodeId 处理的事件数: ${count.get()}")
        }
        
        // 验证所有事件都被处理
        val totalProcessed = processedEvents.values.sumOf { it.get() }
        assertEquals(totalEvents, totalProcessed, "应处理所有发布的事件")
        
        // 等待发布线程完成
        publishThread.join(5000)
    }
    
    @Test
    @Timeout(120)
    fun `should handle packet loss and ensure reliable delivery`() = runBlocking {
        // 创建工作流
        val workflowId = "message-loss-workflow"
        nodes.forEach { (nodeId, node) ->
            val workflow = createTestWorkflow(workflowId, nodeId)
            node.workflowManager.register(workflow)
            node.workflowManager.start(workflowId)
        }
        
        // 等待工作流启动
        delay(2000)
        
        // 准备事件接收机制
        val inputTopic = "loss-input-events"
        val outputTopic = "loss-output-events"
        val totalEvents = 80
        val allEventsLatch = CountDownLatch(totalEvents)
        
        // 重置计数器
        nodes.keys.forEach { nodeId ->
            receivedEvents[nodeId]?.set(0)
            processedEvents[nodeId]?.set(0)
        }
        
        // 在所有节点上订阅输出主题
        nodes.forEach { (nodeId, node) ->
            node.eventBus.subscribe(outputTopic) { event ->
                println("节点 $nodeId 收到处理后的事件: $event")
                processedEvents[nodeId]?.incrementAndGet()
                allEventsLatch.countDown()
            }
        }
        
        // 选择非领导节点作为消息发布节点
        val publisherNodeId = nodes.keys.first { it != initialLeader }
        val publisherNode = nodes[publisherNodeId]!!
        
        // 记录已发布的事件ID，用于验证所有事件都被处理
        val publishedEventIds = ConcurrentHashMap.newKeySet<Int>()
        
        // 使用独立线程发布事件
        val publishThread = Thread {
            try {
                runBlocking {
                    for (i in 1..totalEvents) {
                        // 在发布20个事件后，注入消息丢失故障
                        if (i == 20) {
                            println("在发布20个事件后，注入高丢包率(40%)到领导节点")
                            faultInjector.injectMessageLoss(initialLeader, 0.4, 10000)
                        }
                        
                        // 在发布50个事件后，注入消息丢失故障到发布者节点
                        if (i == 50) {
                            println("在发布50个事件后，注入中等丢包率(20%)到发布者节点")
                            faultInjector.injectMessageLoss(publisherNodeId, 0.2, 10000)
                        }
                        
                        // 发布事件
                        val event = "LossEvent-$i"
                        println("发布事件: $event 到节点 $publisherNodeId")
                        publisherNode.eventBus.publish(event, inputTopic)
                        publishedEventIds.add(i)
                        
                        // 适当延迟
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        publishThread.start()
        
        // 等待所有事件处理完成
        val allEventsProcessed = allEventsLatch.await(120, TimeUnit.SECONDS)
        
        // 验证结果
        assertTrue(allEventsProcessed, "所有事件应在超时前处理完成，即使存在消息丢失")
        
        // 打印统计信息
        println("=== 消息丢失测试事件处理统计 ===")
        processedEvents.forEach { (nodeId, count) ->
            println("节点 $nodeId 处理的事件数: ${count.get()}")
        }
        
        // 验证消息总数
        val totalProcessed = processedEvents.values.sumOf { it.get() }
        assertEquals(totalEvents, totalProcessed, "即使存在网络丢包，所有发布的事件都应被可靠处理")
        
        // 等待发布线程完成
        publishThread.join(5000)
    }
    
    /**
     * 创建测试节点
     */
    private fun createNode(nodeId: String, host: String, port: Int): DisruptorXNode {
        val config = DisruptorXConfig(
            nodeId = nodeId,
            host = host,
            port = port,
            seedNodes = listOf(
                "localhost:29091",
                "localhost:29092",
                "localhost:29093"
            )
        )
        return DisruptorX.createNode(config)
    }
    
    /**
     * 创建测试工作流
     */
    private fun createTestWorkflow(workflowId: String, nodeId: String): Workflow {
        return workflow(workflowId, "Fault Tolerance Test Workflow") {
            source {
                fromTopic("input-events")
                partitionBy { event -> (event as String).hashCode() }
            }
            
            stages {
                stage("processing") {
                    handler { event ->
                        val eventStr = event as String
                        println("节点 $nodeId 处理事件: $eventStr")
                        
                        // 记录收到的事件
                        receivedEvents[nodeId]?.incrementAndGet()
                        
                        // 模拟处理延迟
                        Thread.sleep(100)
                        
                        // 返回处理后的事件
                        "Processed-$eventStr-by-$nodeId"
                    }
                }
            }
            
            sink {
                toTopic("output-events")
            }
        }
    }
    
    /**
     * 查找当前的领导节点
     */
    private fun findLeaderNodeId(): String {
        nodes.forEach { (nodeId, node) ->
            try {
                val leader = node.nodeManager.getLeader()
                if (leader != null) {
                    return leader.nodeId
                }
            } catch (e: Exception) {
                println("节点 $nodeId 无法获取领导信息: ${e.message}")
            }
        }
        return "unknown"
    }
} 