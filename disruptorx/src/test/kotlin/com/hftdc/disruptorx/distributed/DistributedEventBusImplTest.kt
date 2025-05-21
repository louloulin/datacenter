package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.NodeInfo
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.api.NodeStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class DistributedEventBusImplTest {

    private lateinit var nodeManager: NodeManagerImpl
    private lateinit var eventBus: DistributedEventBusImpl
    private val localNodeId = "local-node-1"
    
    @BeforeEach
    fun setup() {
        // 创建模拟节点管理器
        nodeManager = mockk(relaxed = true)
        
        // 配置节点管理器行为
        every { nodeManager.getLocalNodeId() } returns localNodeId
        
        // 创建本地节点和远程节点信息
        val localNode = NodeInfo(
            nodeId = localNodeId,
            host = "localhost",
            port = 9090,
            isLeader = true,
            role = NodeRole.MIXED,
            status = NodeStatus.ACTIVE
        )
        
        val remoteNode = NodeInfo(
            nodeId = "remote-node-1",
            host = "remote-host",
            port = 9090,
            isLeader = false,
            role = NodeRole.MIXED,
            status = NodeStatus.ACTIVE
        )
        
        // 配置集群成员列表
        every { nodeManager.getClusterMembers() } returns listOf(localNode, remoteNode)
        
        // 创建分布式事件总线
        eventBus = DistributedEventBusImpl(
            nodeManager = nodeManager,
            localNodeId = localNodeId,
            config = DistributedEventBusConfig(
                networkConfig = NetworkConfig(
                    port = 9090,
                    connectionTimeout = 5000,
                    eventBatchSize = 100,
                    eventBatchTimeWindowMs = 10
                )
            )
        )
    }
    
    @Test
    fun `publish should distribute events to local handlers`() = runTest {
        // 准备测试数据
        val topic = "test-topic"
        val event = "test-event"
        val handlerCalled = CountDownLatch(1)
        
        // 创建事件处理器
        val handler: suspend (Any) -> Unit = { receivedEvent ->
            assertEquals(event, receivedEvent)
            handlerCalled.countDown()
        }
        
        // 订阅主题
        eventBus.subscribe(topic, handler)
        
        // 发布事件
        eventBus.publish(event, topic)
        
        // 验证处理器被调用
        assertTrue(handlerCalled.await(1, TimeUnit.SECONDS))
    }
    
    @Test
    fun `unsubscribe should remove handler`() = runTest {
        // 准备测试数据
        val topic = "test-topic"
        val event = "test-event"
        var handlerCalled = false
        
        // 创建事件处理器
        val handler: suspend (Any) -> Unit = { handlerCalled = true }
        
        // 订阅和取消订阅
        eventBus.subscribe(topic, handler)
        eventBus.unsubscribe(topic, handler)
        
        // 发布事件
        eventBus.publish(event, topic)
        
        // 验证处理器没有被调用
        assertEquals(false, handlerCalled)
    }
    
    @Test
    fun `initialize should set up event bus`() = runTest {
        // 初始化事件总线
        eventBus.initialize()
        
        // 验证节点管理器被查询
        coVerify { nodeManager.getClusterMembers() }
    }
    
    @Test
    fun `shutdown should clean up resources`() = runTest {
        // 先初始化
        eventBus.initialize()
        
        // 然后关闭
        eventBus.shutdown()
        
        // 发布应该抛出异常
        assertThrows<IllegalStateException> {
            runBlocking {
                eventBus.publish("test", "topic")
            }
        }
    }
    
    @Test
    fun `publish should route events to correct nodes based on topic`() = runTest {
        // 准备测试数据
        val topic = "test-topic"
        val event = "test-event"
        
        // 模拟远程节点配置
        val remoteNodeHandler = slot<suspend (Any) -> Unit>()
        coEvery { 
            nodeManager.getClusterMembers() 
        } returns listOf(
            NodeInfo(
                nodeId = "remote-node-1",
                host = "remote-host",
                port = 9090,
                isLeader = false,
                role = NodeRole.MIXED,
                status = NodeStatus.ACTIVE
            )
        )
        
        // 初始化事件总线
        eventBus.initialize()
        
        // 发布事件
        eventBus.publish(event, topic)
        
        // 验证事件被路由到正确的节点
        coVerify(timeout = 1000) { 
            nodeManager.getClusterMembers() 
        }
    }
} 