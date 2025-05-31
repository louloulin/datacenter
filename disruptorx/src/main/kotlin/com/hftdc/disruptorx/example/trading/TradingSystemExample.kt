package com.hftdc.disruptorx.example.trading

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.example.trading.model.*
import com.hftdc.disruptorx.monitoring.TradingMetricsCollector
import com.hftdc.disruptorx.security.SecurityManager
import com.hftdc.disruptorx.security.UserRole
import com.hftdc.disruptorx.tracing.DistributedTracing
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * 完整的交易系统示例
 * 演示DisruptorX在高频交易场景中的应用
 */
class TradingSystemExample {
    
    private val orderIdGenerator = AtomicLong(1)
    private val tradeIdGenerator = AtomicLong(1)
    
    // 系统组件
    private lateinit var tradingNode: com.hftdc.disruptorx.DisruptorXNode
    private lateinit var metricsCollector: TradingMetricsCollector
    private lateinit var securityManager: SecurityManager
    private lateinit var distributedTracing: DistributedTracing
    
    // 模拟数据
    private val symbols = listOf("AAPL", "GOOGL", "MSFT", "TSLA", "AMZN")
    private val traders = listOf("trader1", "trader2", "trader3", "trader4", "trader5")
    
    suspend fun runExample() {
        println("=== DisruptorX 完整交易系统示例 ===")
        
        try {
            // 1. 初始化系统组件
            initializeSystem()
            
            // 2. 设置安全认证
            setupSecurity()
            
            // 3. 启动交易工作流
            setupTradingWorkflows()
            
            // 4. 模拟交易活动
            simulateTradingActivity()
            
            // 5. 展示系统指标
            displayMetrics()
            
        } catch (e: Exception) {
            println("交易系统运行错误: ${e.message}")
            e.printStackTrace()
        } finally {
            cleanup()
        }
    }
    
    private suspend fun initializeSystem() {
        println("初始化交易系统组件...")
        
        // 创建交易节点
        val config = DisruptorXConfig(
            nodeId = "trading-node-1",
            host = "localhost",
            port = 9090,
            nodeRole = NodeRole.MIXED,
            eventBatchSize = 1000,
            maxConcurrentWorkflows = 50
        )
        
        tradingNode = DisruptorX.createNode(config)
        tradingNode.initialize()
        
        // 初始化监控组件
        metricsCollector = TradingMetricsCollector()
        
        // 初始化安全管理器
        securityManager = SecurityManager("trading-node-1")
        
        // 初始化分布式追踪
        distributedTracing = DistributedTracing("trading-node-1")
        
        println("系统组件初始化完成")
    }
    
    private suspend fun setupSecurity() {
        println("设置安全认证...")
        
        // 创建交易员用户
        traders.forEach { traderId ->
            securityManager.createUser(traderId, "password123", UserRole.TRADER)
        }
        
        // 创建系统管理员
        securityManager.createUser("admin", "admin123", UserRole.ADMIN)
        
        println("安全认证设置完成")
    }
    
    private suspend fun setupTradingWorkflows() {
        println("设置交易工作流...")
        
        // 订阅订单事件
        tradingNode.eventBus.subscribe("orders.new") { event ->
            val order = event as TradeOrder
            processNewOrder(order)
        }
        
        // 订阅市场数据事件
        tradingNode.eventBus.subscribe("market.data") { event ->
            val marketData = event as MarketData
            processMarketData(marketData)
        }
        
        // 订阅交易执行事件
        tradingNode.eventBus.subscribe("trades.executed") { event ->
            val trade = event as Trade
            processExecutedTrade(trade)
        }
        
        println("交易工作流设置完成")
    }
    
    private suspend fun simulateTradingActivity() {
        println("开始模拟交易活动...")
        
        // 启动市场数据生成器
        val marketDataJob = CoroutineScope(Dispatchers.Default).launch {
            generateMarketData()
        }
        
        // 启动订单生成器
        val orderGeneratorJob = CoroutineScope(Dispatchers.Default).launch {
            generateOrders()
        }
        
        // 运行30秒
        delay(30.seconds)
        
        // 停止生成器
        marketDataJob.cancel()
        orderGeneratorJob.cancel()
        
        println("交易活动模拟完成")
    }
    
    private suspend fun generateMarketData() {
        while (true) {
            symbols.forEach { symbol ->
                val marketData = MarketData(
                    symbol = symbol,
                    bidPrice = BigDecimal(Random.nextDouble(100.0, 200.0)),
                    askPrice = BigDecimal(Random.nextDouble(100.0, 200.0)),
                    lastPrice = BigDecimal(Random.nextDouble(100.0, 200.0)),
                    volume = Random.nextLong(1000, 10000),
                    timestamp = System.currentTimeMillis()
                )
                
                tradingNode.eventBus.publish(marketData, "market.data")
            }
            delay(100) // 每100ms更新一次市场数据
        }
    }
    
    private suspend fun generateOrders() {
        while (true) {
            val order = TradeOrder(
                orderId = orderIdGenerator.getAndIncrement().toString(),
                symbol = symbols.random(),
                side = if (Random.nextBoolean()) OrderSide.BUY else OrderSide.SELL,
                quantity = Random.nextLong(100, 1000),
                price = BigDecimal(Random.nextDouble(100.0, 200.0)),
                stopPrice = null,
                clientId = traders.random(),
                type = OrderType.LIMIT,
                timestamp = System.currentTimeMillis()
            )
            
            tradingNode.eventBus.publish(order, "orders.new")
            delay(Random.nextLong(50, 200)) // 随机间隔50-200ms
        }
    }
    
    private suspend fun processNewOrder(order: TradeOrder) {
        val traceContext = distributedTracing.startTrace("process-order")
        
        try {
            val startTime = System.nanoTime()
            
            // 验证订单
            if (validateOrder(order)) {
                order.updateStatus(OrderStatus.VALIDATED)
                
                // 尝试匹配订单
                val matchedTrade = matchOrder(order)
                if (matchedTrade != null) {
                    // 发布交易执行事件
                    tradingNode.eventBus.publish(matchedTrade, "trades.executed")
                }
            } else {
                order.updateStatus(OrderStatus.REJECTED, "订单验证失败")
            }
            
            // 记录延迟指标
            val latency = System.nanoTime() - startTime
            metricsCollector.recordTradeLatency("order-processing", latency)
            
        } catch (e: Exception) {
            distributedTracing.addLog("订单处理错误: ${e.message}")
            order.updateStatus(OrderStatus.REJECTED, "处理错误: ${e.message}")
        }
    }
    
    private suspend fun processMarketData(marketData: MarketData) {
        // 更新市场数据缓存
        // 触发价格变动事件
        // 检查止损订单等
    }
    
    private suspend fun processExecutedTrade(trade: Trade) {
        val startTime = System.nanoTime()
        
        try {
            // 更新持仓
            // 计算盈亏
            // 发送交易确认
            
            println("交易执行: ${trade.symbol} ${trade.side} ${trade.quantity}@${trade.price}")
            
            // 记录交易指标
            metricsCollector.recordThroughput("trade-execution", 1)
            metricsCollector.recordTradeVolume(trade.symbol, trade.price.multiply(BigDecimal(trade.quantity)))

        } finally {
            val latency = System.nanoTime() - startTime
            metricsCollector.recordTradeLatency("trade-execution", latency)
        }
    }
    
    private fun validateOrder(order: TradeOrder): Boolean {
        // 基本验证逻辑
        return order.quantity > 0 &&
               (order.price == null || order.price > BigDecimal.ZERO) &&
               order.symbol.isNotBlank() &&
               order.clientId.isNotBlank()
    }
    
    private fun matchOrder(order: TradeOrder): Trade? {
        // 简化的订单匹配逻辑
        // 实际系统中会有复杂的订单簿匹配算法
        
        if (Random.nextDouble() > 0.3) { // 70%的匹配概率
            return Trade(
                tradeId = tradeIdGenerator.getAndIncrement().toString(),
                orderId = order.orderId,
                symbol = order.symbol,
                side = order.side,
                quantity = order.quantity,
                price = order.price ?: BigDecimal("150.00"), // 市价单使用默认价格
                timestamp = System.currentTimeMillis()
            )
        }

        return null
    }
    
    private suspend fun displayMetrics() {
        println("\n=== 交易系统性能指标 ===")
        
        val stats = metricsCollector.getLatencyStats("order-processing")
        println("延迟统计:")
        println("  P50: ${stats?.p50}")
        println("  P95: ${stats?.p95}")
        println("  P99: ${stats?.p99}")
        println("  P99.9: ${stats?.p999}")
        println("  最大延迟: ${stats?.max}")

        val throughputStats = metricsCollector.getThroughputStats("trade-execution")
        println("\n吞吐量统计:")
        println("  总交易数: ${throughputStats?.totalCount}")
        println("  每秒交易数: ${throughputStats?.ratePerSecond}")
        println("  峰值TPS: ${throughputStats?.peakRate}")

        val volumeStats = metricsCollector.getTradeVolumeStats()
        println("\n交易量统计:")
        volumeStats.forEach { (symbol, volume) ->
            println("  $symbol: $volume")
        }
        
        // 显示安全审计日志
        val auditLogs = securityManager.getAuditLogs(limit = 10)
        println("\n最近的安全审计日志:")
        auditLogs.takeLast(5).forEach { log ->
            println("  ${log.timestamp}: ${log.action} - ${log.result}")
        }
    }
    
    private suspend fun cleanup() {
        println("清理系统资源...")
        
        try {
            tradingNode.shutdown()
            distributedTracing.flush()
        } catch (e: Exception) {
            println("清理过程中发生错误: ${e.message}")
        }
        
        println("系统清理完成")
    }
}

/**
 * 主函数
 */
suspend fun main() {
    val example = TradingSystemExample()
    example.runExample()
}
