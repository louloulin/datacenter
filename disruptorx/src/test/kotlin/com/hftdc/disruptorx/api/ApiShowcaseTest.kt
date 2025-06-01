package com.hftdc.disruptorx.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * API展示测试 - 演示api.md中设计的所有功能
 */
class ApiShowcaseTest {

    // 测试事件类
    data class OrderEvent(val orderId: String, val amount: Double, val customerId: String = "")
    data class PaymentEvent(val paymentId: String, val amount: Double)
    data class InventoryEvent(val itemId: String, val quantity: Int)

    @Test
    fun `展示1 - 3行代码启动最简单用例`() {
        println("\n=== 展示1: 3行代码启动 ===")
        
        // 这就是api.md中承诺的3行代码启动
        val bus = eventBus<OrderEvent>()
        bus.on { order -> println("处理订单: ${order.orderId}, 金额: ${order.amount}") }
        bus.start()
        
        bus.emit(OrderEvent("ORDER-1", 100.0))
        bus.emit(OrderEvent("ORDER-2", 200.0))
        
        Thread.sleep(100) // 等待处理
        bus.close()
        
        println("✅ 3行代码启动展示完成")
    }

    @Test
    fun `展示2 - 分布式配置5行代码启动`() {
        println("\n=== 展示2: 分布式配置 ===")
        
        // 分布式事件处理 - 5行代码启动
        val bus = eventBus<OrderEvent> {
            distributed("cluster.example.com:9090")
        }
        bus.on { order -> println("分布式处理订单: ${order.orderId}") }
        bus.start()
        
        bus.emit(OrderEvent("DIST-ORDER-1", 500.0))
        
        Thread.sleep(100)
        bus.close()
        
        println("✅ 分布式配置展示完成")
    }

    @Test
    fun `展示3 - 渐进式复杂度设计`() = runBlocking {
        println("\n=== 展示3: 渐进式复杂度 ===")
        
        val bus = eventBus<OrderEvent>()
        bus.start()
        
        // Level 1: 基础事件处理
        println("Level 1: 基础事件处理")
        bus.on { order -> println("  基础处理: ${order.orderId}") }
        
        // Level 2: 过滤和条件处理
        println("Level 2: 过滤和条件处理")
        bus.filter { it.amount > 1000 }.on { order ->
            println("  高价值订单: ${order.orderId}, 金额: ${order.amount}")
        }
        
        // Level 3: 异步处理
        println("Level 3: 异步处理")
        bus.onAsync { order ->
            kotlinx.coroutines.delay(10) // 模拟异步处理
            println("  异步处理完成: ${order.orderId}")
        }
        
        // 发布测试事件
        bus.emit(OrderEvent("BASIC-1", 500.0))
        bus.emit(OrderEvent("HIGH-VALUE-1", 1500.0))
        bus.emit(OrderEvent("BASIC-2", 300.0))
        
        kotlinx.coroutines.delay(200) // 等待异步处理
        bus.close()
        
        println("✅ 渐进式复杂度展示完成")
    }

    @Test
    fun `展示4 - Kotlin DSL特性`() {
        println("\n=== 展示4: Kotlin DSL特性 ===")
        
        val bus = eventBus<OrderEvent> {
            ringBufferSize = 2048
            waitStrategy = WaitStrategy.BUSY_SPIN
            producerType = ProducerType.SINGLE
            
            performance {
                batchSize = 200
                enableZeroCopy = true
                objectPoolSize = 2000
            }
            
            monitoring {
                metrics = true
                logging = LogLevel.DEBUG
            }
            
            backpressure {
                strategy = BackpressureStrategy.DROP_OLDEST
                bufferSize = 5000
            }
        }
        
        bus.on { order -> println("高性能处理: ${order.orderId}") }
        bus.start()
        
        bus.emit(OrderEvent("PERF-1", 100.0))
        
        Thread.sleep(100)
        bus.close()
        
        println("✅ Kotlin DSL特性展示完成")
    }

    @Test
    fun `展示5 - 扩展函数和链式操作`() {
        println("\n=== 展示5: 扩展函数和链式操作 ===")
        
        val bus = eventBus<OrderEvent>().started()
        
        // 链式过滤和处理
        bus.where { it.amount > 100 }
            .on { order -> println("过滤后的订单: ${order.orderId}") }
        
        // 一次性处理器
        bus.once { order -> println("一次性处理: ${order.orderId}") }
        
        // 条件处理器
        bus.onWhen({ it.customerId.isNotEmpty() }) { order ->
            println("有客户ID的订单: ${order.orderId}, 客户: ${order.customerId}")
        }
        
        // 使用扩展函数发布
        listOf(
            OrderEvent("EXT-1", 150.0, "CUST-1"),
            OrderEvent("EXT-2", 50.0),  // 会被过滤
            OrderEvent("EXT-3", 200.0, "CUST-2")
        ).emitTo(bus)
        
        Thread.sleep(100)
        bus.close()
        
        println("✅ 扩展函数和链式操作展示完成")
    }

    @Test
    fun `展示6 - 协程友好的异步API`() = runBlocking {
        println("\n=== 展示6: 协程友好的异步API ===")
        
        val bus = eventBus<OrderEvent>().started()
        val processedCount = AtomicInteger(0)
        val latch = CountDownLatch(3)
        
        // 异步事件处理器
        bus.onAsync { order ->
            kotlinx.coroutines.delay(50) // 模拟异步处理
            processedCount.incrementAndGet()
            println("异步处理完成: ${order.orderId}")
            latch.countDown()
        }
        
        // 异步发布事件
        bus.emitAsync(OrderEvent("ASYNC-1", 100.0))
        bus.emitAsync(OrderEvent("ASYNC-2", 200.0))
        bus.emitAsync(OrderEvent("ASYNC-3", 300.0))
        
        latch.await(2, TimeUnit.SECONDS)
        bus.close()
        
        println("处理了 ${processedCount.get()} 个异步事件")
        println("✅ 协程友好的异步API展示完成")
    }

    @Test
    fun `展示7 - 性能监控和健康检查`() {
        println("\n=== 展示7: 性能监控和健康检查 ===")
        
        val bus = eventBus<OrderEvent> {
            monitoring {
                metrics = true
                tracing = false
                logging = LogLevel.INFO
            }
        }.started()
        
        bus.on { order -> 
            // 模拟处理
            Thread.sleep(1)
        }
        
        // 发布一些事件来生成指标
        repeat(10) { i ->
            bus.emit(OrderEvent("METRIC-$i", i * 100.0))
        }
        
        Thread.sleep(100) // 等待处理完成
        
        // 检查性能指标
        val metrics = bus.metrics
        println("吞吐量: ${metrics.throughput} events")
        println("错误率: ${metrics.errorRate}")
        println("队列大小: ${metrics.queueSize}")
        
        // 检查健康状态
        val health = bus.health
        println("健康状态: ${health.isHealthy}")
        println("状态: ${health.status}")
        println("详细信息: ${health.details}")
        
        bus.close()
        
        println("✅ 性能监控和健康检查展示完成")
    }

    @Test
    fun `展示8 - 微服务事件驱动架构示例`() {
        println("\n=== 展示8: 微服务事件驱动架构 ===")
        
        // 订单服务事件总线
        val orderBus = eventBus<OrderEvent>().started()
        
        // 支付服务事件总线
        val paymentBus = eventBus<PaymentEvent>().started()
        
        // 库存服务事件总线
        val inventoryBus = eventBus<InventoryEvent>().started()
        
        val processedOrders = AtomicInteger(0)
        val processedPayments = AtomicInteger(0)
        val processedInventory = AtomicInteger(0)
        
        // 订单处理 - 触发支付和库存预留
        orderBus.on { order ->
            println("📦 处理订单: ${order.orderId}")
            processedOrders.incrementAndGet()
            
            // 触发支付处理
            paymentBus.emit(PaymentEvent("PAY-${order.orderId}", order.amount))
            
            // 触发库存预留
            inventoryBus.emit(InventoryEvent("ITEM-${order.orderId}", 1))
        }
        
        // 支付处理
        paymentBus.on { payment ->
            println("💳 处理支付: ${payment.paymentId}, 金额: ${payment.amount}")
            processedPayments.incrementAndGet()
        }
        
        // 库存处理
        inventoryBus.on { inventory ->
            println("📋 预留库存: ${inventory.itemId}, 数量: ${inventory.quantity}")
            processedInventory.incrementAndGet()
        }
        
        // 发布订单事件
        orderBus.emit(OrderEvent("ORDER-MS-1", 299.99))
        orderBus.emit(OrderEvent("ORDER-MS-2", 599.99))
        
        Thread.sleep(200) // 等待处理完成
        
        println("处理统计:")
        println("  订单: ${processedOrders.get()}")
        println("  支付: ${processedPayments.get()}")
        println("  库存: ${processedInventory.get()}")
        
        orderBus.close()
        paymentBus.close()
        inventoryBus.close()
        
        println("✅ 微服务事件驱动架构展示完成")
    }

    @Test
    fun `展示9 - 生命周期管理和资源清理`() {
        println("\n=== 展示9: 生命周期管理 ===")
        
        val bus = eventBus<OrderEvent>()
        
        // 检查初始状态
        println("初始状态: ${bus.health.status}")
        assert(!bus.health.isHealthy) { "未启动的总线应该不健康" }
        
        // 启动
        bus.start()
        println("启动后状态: ${bus.health.status}")
        assert(bus.health.isHealthy) { "启动后的总线应该健康" }
        
        // 使用总线
        bus.on { order -> println("处理: ${order.orderId}") }
        bus.emit(OrderEvent("LIFECYCLE-1", 100.0))
        
        Thread.sleep(50)
        
        // 停止
        bus.stop()
        println("停止后状态: ${bus.health.status}")
        assert(!bus.health.isHealthy) { "停止后的总线应该不健康" }
        
        // 关闭和资源清理
        bus.close()
        println("关闭完成")
        
        println("✅ 生命周期管理展示完成")
    }
}
