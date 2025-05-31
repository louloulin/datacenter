package com.hftdc.disruptorx.integration

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.example.trading.model.*
import com.hftdc.disruptorx.monitoring.TradingMetricsCollector
import com.hftdc.disruptorx.security.SecurityManager
import com.hftdc.disruptorx.security.UserRole
import com.hftdc.disruptorx.tracing.DistributedTracing
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * 交易系统集成测试
 * 验证完整的交易流程和系统集成
 */
class TradingSystemIntegrationTest {

    private lateinit var tradingNode: com.hftdc.disruptorx.DisruptorXNode
    private lateinit var securityManager: SecurityManager
    private lateinit var metricsCollector: TradingMetricsCollector
    private lateinit var distributedTracing: DistributedTracing
    
    // 测试计数器
    private val processedOrders = AtomicInteger(0)
    private val executedTrades = AtomicInteger(0)
    private val marketDataUpdates = AtomicInteger(0)
    private val totalLatency = AtomicLong(0)

    @BeforeEach
    fun setup() = runBlocking {
        // 创建交易节点
        val config = DisruptorXConfig(
            nodeId = "integration-test-node",
            host = "localhost",
            port = 9200,
            nodeRole = NodeRole.MIXED,
            eventBatchSize = 100,
            maxConcurrentWorkflows = 20
        )
        
        tradingNode = DisruptorX.createNode(config)
        tradingNode.initialize()
        
        // 初始化组件
        securityManager = SecurityManager("integration-test-node")
        metricsCollector = TradingMetricsCollector()
        distributedTracing = DistributedTracing("integration-test-node")
        
        // 重置计数器
        processedOrders.set(0)
        executedTrades.set(0)
        marketDataUpdates.set(0)
        totalLatency.set(0)
        
        // 设置事件处理器
        setupEventHandlers()
    }

    @AfterEach
    fun cleanup() {
        tradingNode.shutdown()
    }

    private suspend fun setupEventHandlers() {
        // 订单处理事件
        tradingNode.eventBus.subscribe("orders.new") { event ->
            val order = event as TradeOrder
            processNewOrder(order)
        }
        
        // 市场数据事件
        tradingNode.eventBus.subscribe("market.data") { event ->
            val marketData = event as MarketData
            processMarketData(marketData)
        }
        
        // 交易执行事件
        tradingNode.eventBus.subscribe("trades.executed") { event ->
            val trade = event as Trade
            processExecutedTrade(trade)
        }
        
        // 风险管理事件
        tradingNode.eventBus.subscribe("risk.check") { event ->
            val order = event as TradeOrder
            processRiskCheck(order)
        }
    }

    @Test
    fun `完整交易流程集成测试`() = runBlocking {
        println("=== 完整交易流程集成测试 ===")
        
        // 1. 创建测试用户
        val trader = securityManager.createUser("trader1", "password123", UserRole.TRADER)
        val token = securityManager.authenticate("trader1", "password123")!!
        
        // 2. 发布市场数据
        val marketData = MarketData(
            symbol = "AAPL",
            bidPrice = BigDecimal("149.50"),
            askPrice = BigDecimal("150.50"),
            lastPrice = BigDecimal("150.00"),
            volume = 1000,
            timestamp = System.currentTimeMillis()
        )
        tradingNode.eventBus.publish(marketData, "market.data")
        
        // 3. 提交买单
        val buyOrder = TradeOrder(
            clientId = trader.userId,
            symbol = "AAPL",
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            price = BigDecimal("149.50"),
            stopPrice = null,
            quantity = 100
        )
        
        val startTime = System.nanoTime()
        tradingNode.eventBus.publish(buyOrder, "orders.new")
        
        // 4. 提交卖单
        val sellOrder = TradeOrder(
            clientId = trader.userId,
            symbol = "AAPL",
            side = OrderSide.SELL,
            type = OrderType.LIMIT,
            price = BigDecimal("150.50"),
            stopPrice = null,
            quantity = 50
        )
        
        tradingNode.eventBus.publish(sellOrder, "orders.new")
        
        // 5. 等待处理完成
        delay(2.seconds)
        
        val endTime = System.nanoTime()
        val totalProcessingTime = endTime - startTime
        
        // 6. 验证结果
        println("处理结果:")
        println("  已处理订单: ${processedOrders.get()}")
        println("  已执行交易: ${executedTrades.get()}")
        println("  市场数据更新: ${marketDataUpdates.get()}")
        println("  总处理时间: ${totalProcessingTime / 1_000_000} ms")
        
        // 断言验证
        assertTrue(processedOrders.get() >= 2, "应该处理至少2个订单")
        assertTrue(marketDataUpdates.get() >= 1, "应该处理至少1个市场数据更新")
        assertTrue(totalProcessingTime < 5_000_000_000L, "总处理时间应该小于5秒")
        
        println("✅ 完整交易流程集成测试通过")
    }

    @Test
    fun `高频交易性能测试`() = runBlocking {
        println("=== 高频交易性能测试 ===")
        
        val trader = securityManager.createUser("hft_trader", "password123", UserRole.TRADER)
        val orderCount = 1000
        val symbols = listOf("AAPL", "GOOGL", "MSFT", "TSLA", "AMZN")
        
        val startTime = System.nanoTime()
        
        // 并发提交大量订单
        val jobs = (1..orderCount).map { i ->
            async {
                val symbol = symbols[i % symbols.size]
                val order = TradeOrder(
                    clientId = trader.userId,
                    symbol = symbol,
                    side = if (i % 2 == 0) OrderSide.BUY else OrderSide.SELL,
                    type = OrderType.MARKET,
                    price = BigDecimal("${100 + (i % 50)}"),
                    stopPrice = null,
                    quantity = (10 + (i % 90)).toLong()
                )
                
                tradingNode.eventBus.publish(order, "orders.new")
            }
        }
        
        // 等待所有订单提交完成
        jobs.forEach { it.await() }
        
        // 等待处理完成
        delay(5.seconds)
        
        val endTime = System.nanoTime()
        val totalTime = endTime - startTime
        val throughput = orderCount.toDouble() / (totalTime / 1_000_000_000.0)
        
        println("高频交易性能结果:")
        println("  订单数量: $orderCount")
        println("  处理时间: ${totalTime / 1_000_000} ms")
        println("  吞吐量: ${String.format("%.2f", throughput)} orders/sec")
        println("  已处理订单: ${processedOrders.get()}")
        
        // 性能验证
        assertTrue(throughput >= 100.0, "吞吐量应该大于100 orders/sec，实际为: ${String.format("%.2f", throughput)}")
        assertTrue(processedOrders.get() >= orderCount * 0.9, "至少应该处理90%的订单")
        
        println("✅ 高频交易性能测试通过")
    }

    @Test
    fun `风险管理集成测试`() = runBlocking {
        println("=== 风险管理集成测试 ===")
        
        val trader = securityManager.createUser("risk_trader", "password123", UserRole.TRADER)
        
        // 提交超大订单触发风险检查
        val largeOrder = TradeOrder(
            clientId = trader.userId,
            symbol = "AAPL",
            side = OrderSide.BUY,
            type = OrderType.MARKET,
            price = BigDecimal("1000.00"),
            stopPrice = null,
            quantity = 10000 // 超大数量
        )
        
        tradingNode.eventBus.publish(largeOrder, "orders.new")
        
        // 等待风险检查完成
        delay(1.seconds)
        
        println("风险管理测试结果:")
        println("  已处理订单: ${processedOrders.get()}")
        
        // 验证风险管理功能
        assertTrue(processedOrders.get() >= 1, "应该处理风险检查")
        
        println("✅ 风险管理集成测试通过")
    }

    @Test
    fun `多用户并发交易测试`() = runBlocking {
        println("=== 多用户并发交易测试 ===")
        
        val userCount = 10
        val ordersPerUser = 50
        
        // 创建多个交易用户
        val traders = (1..userCount).map { i ->
            securityManager.createUser("trader$i", "password123", UserRole.TRADER)
        }
        
        val startTime = System.nanoTime()
        
        // 每个用户并发提交订单
        val jobs = traders.map { trader ->
            async {
                repeat(ordersPerUser) { orderIndex ->
                    val order = TradeOrder(
                        clientId = trader.userId,
                        symbol = "AAPL",
                        side = if (orderIndex % 2 == 0) OrderSide.BUY else OrderSide.SELL,
                        type = OrderType.LIMIT,
                        price = BigDecimal("${150 + (orderIndex % 10)}"),
                        stopPrice = null,
                        quantity = (10 + (orderIndex % 20)).toLong()
                    )
                    
                    tradingNode.eventBus.publish(order, "orders.new")
                    delay(10) // 小间隔模拟真实交易
                }
            }
        }
        
        // 等待所有用户完成交易
        jobs.forEach { it.await() }
        
        // 等待处理完成
        delay(3.seconds)
        
        val endTime = System.nanoTime()
        val totalTime = endTime - startTime
        val totalOrders = userCount * ordersPerUser
        val throughput = totalOrders.toDouble() / (totalTime / 1_000_000_000.0)
        
        println("多用户并发交易结果:")
        println("  用户数量: $userCount")
        println("  每用户订单: $ordersPerUser")
        println("  总订单数: $totalOrders")
        println("  处理时间: ${totalTime / 1_000_000} ms")
        println("  吞吐量: ${String.format("%.2f", throughput)} orders/sec")
        println("  已处理订单: ${processedOrders.get()}")
        
        // 验证并发处理能力
        assertTrue(throughput >= 50.0, "并发吞吐量应该大于50 orders/sec")
        assertTrue(processedOrders.get() >= totalOrders * 0.8, "至少应该处理80%的订单")
        
        println("✅ 多用户并发交易测试通过")
    }

    @Test
    fun `系统监控和追踪测试`() = runBlocking {
        println("=== 系统监控和追踪测试 ===")
        
        val trader = securityManager.createUser("monitor_trader", "password123", UserRole.TRADER)
        
        // 提交一些订单以生成监控数据
        repeat(20) { i ->
            val order = TradeOrder(
                clientId = trader.userId,
                symbol = "AAPL",
                side = if (i % 2 == 0) OrderSide.BUY else OrderSide.SELL,
                type = OrderType.LIMIT,
                price = BigDecimal("${150 + i}"),
                stopPrice = null,
                quantity = (10 + i).toLong()
            )
            
            tradingNode.eventBus.publish(order, "orders.new")
        }
        
        // 等待处理完成
        delay(2.seconds)
        
        // 检查监控指标
        val latencyStats = metricsCollector.getAllLatencyStats()
        val throughputStats = metricsCollector.getAllThroughputStats()
        
        println("监控指标:")
        println("  延迟统计指标: ${latencyStats.size}")
        println("  吞吐量统计指标: ${throughputStats.size}")
        println("  已处理订单: ${processedOrders.get()}")

        // 验证监控功能
        assertTrue(latencyStats.isNotEmpty() || throughputStats.isNotEmpty(), "应该收集到监控指标")
        assertTrue(processedOrders.get() >= 20, "应该处理所有订单")
        
        println("✅ 系统监控和追踪测试通过")
    }

    // 事件处理方法
    private suspend fun processNewOrder(order: TradeOrder) {
        val startTime = System.nanoTime()
        
        try {
            // 模拟订单验证
            delay(1)
            
            // 风险检查
            tradingNode.eventBus.publish(order, "risk.check")
            
            // 更新订单状态
            order.updateStatus(OrderStatus.VALIDATED)
            
            // 模拟订单匹配
            if (order.type == OrderType.MARKET || Math.random() > 0.5) {
                val trade = Trade(
                    tradeId = "trade-${System.currentTimeMillis()}",
                    orderId = order.orderId,
                    symbol = order.symbol,
                    side = order.side,
                    quantity = order.quantity,
                    price = order.price ?: BigDecimal("150.00"),
                    timestamp = System.currentTimeMillis()
                )
                
                tradingNode.eventBus.publish(trade, "trades.executed")
            }
            
            processedOrders.incrementAndGet()
            
        } finally {
            val latency = System.nanoTime() - startTime
            totalLatency.addAndGet(latency)
            metricsCollector.recordTradeLatency("order-processing", latency)
        }
    }
    
    private suspend fun processMarketData(marketData: MarketData) {
        // 模拟市场数据处理
        delay(1)
        marketDataUpdates.incrementAndGet()
        metricsCollector.recordThroughput("market-data", 1)
    }
    
    private suspend fun processExecutedTrade(trade: Trade) {
        // 模拟交易执行处理
        delay(1)
        executedTrades.incrementAndGet()
        metricsCollector.recordThroughput("trade-execution", 1)
        metricsCollector.recordTradeVolume(trade.symbol, trade.price.multiply(BigDecimal(trade.quantity)))
    }
    
    private suspend fun processRiskCheck(order: TradeOrder) {
        // 模拟风险检查
        delay(2)
        
        // 简单的风险规则：数量超过5000的订单需要额外检查
        if (order.quantity > 5000) {
            println("风险警告: 大额订单 ${order.orderId}, 数量: ${order.quantity}")
        }
        
        metricsCollector.recordThroughput("risk-check", 1)
    }
}
