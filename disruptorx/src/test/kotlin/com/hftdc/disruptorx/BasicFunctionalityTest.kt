package com.hftdc.disruptorx

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.api.NodeRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * DisruptorX 基础功能测试
 * 验证核心组件的基本功能
 */
class BasicFunctionalityTest {
    
    @Test
    fun `test node creation and initialization`() = runBlocking {
        // 创建节点配置
        val config = DisruptorXConfig(
            nodeId = "test-node-1",
            host = "localhost",
            port = 8081,
            nodeRole = NodeRole.MIXED
        )
        
        // 创建节点
        val node = DisruptorX.createNode(config)
        assertNotNull(node)
        assertNotNull(node.eventBus)
        assertNotNull(node.workflowManager)
        
        try {
            // 初始化节点
            node.initialize()
            
            // 验证节点已正确初始化
            assertTrue(true) // 如果没有异常，说明初始化成功
            
        } finally {
            node.shutdown()
        }
    }
    
    @Test
    fun `test event publishing and subscription`() = runBlocking {
        val config = DisruptorXConfig(
            nodeId = "test-node-2",
            host = "localhost",
            port = 8082,
            nodeRole = NodeRole.MIXED
        )
        
        val node = DisruptorX.createNode(config)
        val receivedEvents = mutableListOf<String>()
        val eventCount = AtomicInteger(0)
        
        try {
            node.initialize()
            
            // 订阅事件
            node.eventBus.subscribe("test.topic") { event ->
                receivedEvents.add(event.toString())
                eventCount.incrementAndGet()
            }
            
            // 发布事件
            val testEvents = listOf("事件1", "事件2", "事件3")
            testEvents.forEach { event ->
                node.eventBus.publish(event, "test.topic")
            }
            
            // 等待事件处理
            delay(500)
            
            // 验证事件接收
            assertEquals(testEvents.size, eventCount.get())
            assertTrue(receivedEvents.containsAll(testEvents))
            
        } finally {
            node.shutdown()
        }
    }
    
    @Test
    fun `test multiple topic subscription`() = runBlocking {
        val config = DisruptorXConfig(
            nodeId = "test-node-3",
            host = "localhost",
            port = 8083,
            nodeRole = NodeRole.MIXED
        )
        
        val node = DisruptorX.createNode(config)
        val topic1Events = mutableListOf<String>()
        val topic2Events = mutableListOf<String>()
        
        try {
            node.initialize()
            
            // 订阅多个主题
            node.eventBus.subscribe("topic1") { event ->
                topic1Events.add(event.toString())
            }
            
            node.eventBus.subscribe("topic2") { event ->
                topic2Events.add(event.toString())
            }
            
            // 发布到不同主题
            node.eventBus.publish("Topic1事件", "topic1")
            node.eventBus.publish("Topic2事件", "topic2")
            
            delay(300)
            
            // 验证事件正确路由
            assertEquals(1, topic1Events.size)
            assertEquals(1, topic2Events.size)
            assertEquals("Topic1事件", topic1Events[0])
            assertEquals("Topic2事件", topic2Events[0])
            
        } finally {
            node.shutdown()
        }
    }
    
    @Test
    fun `test node configuration`() {
        val config = DisruptorXConfig(
            nodeId = "custom-node",
            host = "192.168.1.100",
            port = 9090,
            nodeRole = NodeRole.COORDINATOR,
            heartbeatIntervalMillis = 1000,
            eventBatchSize = 50
        )
        
        assertEquals("custom-node", config.nodeId)
        assertEquals("192.168.1.100", config.host)
        assertEquals(9090, config.port)
        assertEquals(NodeRole.COORDINATOR, config.nodeRole)
        assertEquals(1000, config.heartbeatIntervalMillis)
        assertEquals(50, config.eventBatchSize)
    }
}