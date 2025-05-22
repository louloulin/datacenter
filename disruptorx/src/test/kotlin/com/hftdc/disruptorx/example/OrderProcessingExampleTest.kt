package com.hftdc.disruptorx.example

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.api.WorkflowStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OrderProcessingExampleTest {

    private val node = DisruptorX.createNode(
        DisruptorXConfig(
            nodeId = "test-node",
            host = "localhost",
            port = 9091, // 使用不同端口避免和其他测试冲突
            nodeRole = NodeRole.COORDINATOR
        )
    )
    
    @BeforeEach
    fun setup() {
        // 初始化节点
        node.initialize()
    }
    
    @AfterEach
    fun cleanup() {
        // 关闭节点
        node.shutdown()
    }
    
    @Test
    @Disabled("集成测试需要实际的网络通信，暂时禁用")
    fun `should process orders through workflow`() = runBlocking {
        // 创建工作流
        val workflow = OrderProcessingExample.createOrderProcessingWorkflow()
        
        // 注册并启动工作流
        node.workflowManager.register(workflow)
        node.workflowManager.start(workflow.id)
        
        // 等待工作流启动
        delay(500)
        
        // 验证工作流状态
        assertEquals(WorkflowStatus.RUNNING, node.workflowManager.status(workflow.id))
        
        // 准备接收处理后的订单
        val processedCount = AtomicInteger(0)
        val processedLatch = CountDownLatch(5) // 等待5个订单处理完成
        
        // 订阅处理后的订单
        node.eventBus.subscribe("processed-orders") { event ->
            val order = event as Order
            println("Received processed order: ${order.orderId}")
            assertEquals(OrderStatus.PROCESSED, order.status)
            processedCount.incrementAndGet()
            processedLatch.countDown()
        }
        
        // 生成5个测试订单
        val orders = generateTestOrders(5)
        
        // 发布测试订单
        orders.forEach { order ->
            node.eventBus.publish(order, "orders")
        }
        
        // 等待订单处理完成
        val processed = processedLatch.await(5, TimeUnit.SECONDS)
        
        // 验证所有订单都被处理
        assertEquals(true, processed, "所有订单应该在超时前处理完成")
        assertEquals(5, processedCount.get(), "应该处理5个订单")
        
        // 停止工作流
        node.workflowManager.stop(workflow.id)
    }
    
    /**
     * 模拟集成测试的单元测试版本
     */
    @Test
    fun `should create and configure workflow correctly`() {
        // 创建工作流
        val workflow = OrderProcessingExample.createOrderProcessingWorkflow()
        
        // 验证工作流基本配置
        assertEquals("orderProcessing", workflow.id)
        assertEquals("Order Processing Workflow", workflow.name)
        
        // 验证工作流结构
        assertEquals("orders", workflow.source.topic)
        assertEquals("processed-orders", workflow.sink.topic)
        
        // 验证处理阶段
        assertEquals(4, workflow.stages.size)
        assertEquals("validation", workflow.stages[0].id)
        assertEquals("enrichment", workflow.stages[1].id)
        assertEquals("processing", workflow.stages[2].id)
        assertEquals(4, workflow.stages[2].parallelism) // 并行度设置为4
        assertEquals("notification", workflow.stages[3].id)
    }
    
    /**
     * 生成测试订单
     */
    private fun generateTestOrders(count: Int): List<Order> {
        return (1..count).map { i ->
            Order(
                orderId = "TEST-ORD-$i",
                customerId = "TEST-CUST-${i % 3 + 1}",
                items = listOf(
                    OrderItem("ITEM-1", "Test Product 1", 10.0, 2),
                    OrderItem("ITEM-2", "Test Product 2", 15.0, 1)
                ),
                status = OrderStatus.CREATED
            )
        }
    }
} 