package com.hftdc.disruptorx.example

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.DisruptorXNode
import com.hftdc.disruptorx.api.Workflow
import com.hftdc.disruptorx.dsl.workflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 订单处理示例
 * 演示如何使用DisruptorX框架处理订单
 */
object OrderProcessingExample {
    
    /**
     * 示例入口点
     */
    @JvmStatic
    fun main(args: Array<String>) {
        // 创建DisruptorX节点
        val node = createNode()
        
        // 创建并注册工作流
        val workflow = createOrderProcessingWorkflow()
        node.workflowManager.register(workflow)
        
        // 启动工作流
        node.workflowManager.start(workflow.id)
        
        // 发布一些测试订单
        runBlocking {
            val orders = generateTestOrders(10)
            for (order in orders) {
                node.eventBus.publish(order, "orders")
                delay(100) // 短暂延迟以便观察处理过程
            }
            
            // 等待处理完成
            delay(1000)
        }
        
        // 停止工作流和节点
        node.workflowManager.stop(workflow.id)
        node.shutdown()
    }
    
    /**
     * 创建DisruptorX节点
     */
    private fun createNode(): DisruptorXNode {
        val config = DisruptorXConfig(
            host = "localhost",
            port = 9090
        )
        
        val node = DisruptorX.createNode(config)
        node.initialize()
        
        return node
    }
    
    /**
     * 创建订单处理工作流
     */
    private fun createOrderProcessingWorkflow(): Workflow {
        return workflow("orderProcessing", "Order Processing Workflow") {
            source {
                fromTopic("orders")
                partitionBy { order -> (order as Order).orderId.hashCode() }
            }
            
            stages {
                stage("validation") {
                    handler { event ->
                        val order = event as Order
                        println("Validating order: ${order.orderId}")
                        
                        // 验证逻辑
                        if (order.items.isEmpty()) {
                            println("  Order rejected: No items")
                        } else {
                            println("  Order valid")
                        }
                    }
                }
                
                stage("enrichment") {
                    handler { event ->
                        val order = event as Order
                        println("Enriching order: ${order.orderId}")
                        
                        // 充实订单信息
                        order.totalPrice = order.items.sumOf { it.price * it.quantity }
                        println("  Total price: ${order.totalPrice}")
                    }
                }
                
                stage("processing") {
                    parallelism = 4 // 使用4个并行处理器
                    handler { event ->
                        val order = event as Order
                        println("Processing order: ${order.orderId}")
                        
                        // 处理订单
                        order.status = OrderStatus.PROCESSED
                        println("  Order processed")
                    }
                }
                
                stage("notification") {
                    handler { event ->
                        val order = event as Order
                        println("Sending notification for order: ${order.orderId}")
                        
                        // 发送通知
                        println("  Notification sent to customer: ${order.customerId}")
                    }
                }
            }
            
            sink {
                toTopic("processed-orders")
            }
        }
    }
    
    /**
     * 生成测试订单
     */
    private fun generateTestOrders(count: Int): List<Order> {
        return (1..count).map { i ->
            Order(
                orderId = "ORD-$i",
                customerId = "CUST-${i % 3 + 1}",
                items = listOf(
                    OrderItem("ITEM-1", "Product 1", 10.0, 2),
                    OrderItem("ITEM-2", "Product 2", 15.0, 1)
                ),
                status = OrderStatus.CREATED
            )
        }
    }
}

/**
 * 订单数据类
 */
data class Order(
    val orderId: String,
    val customerId: String,
    val items: List<OrderItem>,
    var status: OrderStatus = OrderStatus.CREATED,
    var totalPrice: Double = 0.0
)

/**
 * 订单项数据类
 */
data class OrderItem(
    val itemId: String,
    val name: String,
    val price: Double,
    val quantity: Int
)

/**
 * 订单状态枚举
 */
enum class OrderStatus {
    CREATED,
    VALIDATED,
    PROCESSED,
    SHIPPED,
    DELIVERED,
    CANCELLED
} 