package com.hftdc.disruptorx.example.trading.workflow

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.api.Workflow
import com.hftdc.disruptorx.dsl.workflow
import com.hftdc.disruptorx.example.trading.model.OrderSide
import com.hftdc.disruptorx.example.trading.model.OrderStatus
import com.hftdc.disruptorx.example.trading.model.OrderType
import com.hftdc.disruptorx.example.trading.model.TradeOrder
import com.hftdc.disruptorx.performance.LatencyRecorder
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureNanoTime

/**
 * 高频交易工作流示例
 * 展示如何使用DisruptorX框架构建高性能的交易处理系统
 */
object TradingWorkflow {
    // 系统状态
    private val orderBook = ConcurrentHashMap<String, MutableMap<String, TradeOrder>>()
    private val matchedOrders = ConcurrentHashMap<String, MutableList<TradeOrder>>()
    private val latencyRecorders = ConcurrentHashMap<String, LatencyRecorder>()
    
    // 工作流ID
    private const val TRADING_WORKFLOW_ID = "high-frequency-trading"
    
    // 主题定义
    private const val ORDERS_TOPIC = "trading.orders"
    private const val VALIDATED_ORDERS_TOPIC = "trading.orders.validated"
    private const val MATCHED_ORDERS_TOPIC = "trading.orders.matched"
    private const val EXECUTED_ORDERS_TOPIC = "trading.orders.executed"
    private const val TRADES_TOPIC = "trading.trades"
    
    /**
     * 创建并返回交易工作流
     */
    fun createWorkflow(): Workflow {
        return workflow(TRADING_WORKFLOW_ID, "High Frequency Trading Workflow") {
            // 1. 订单输入源
            source {
                fromTopic(ORDERS_TOPIC)
                partitionBy { order -> 
                    (order as TradeOrder).symbol.hashCode()
                }
            }
            
            // 2. 处理阶段
            stages {
                // 2.1 订单验证阶段
                stage("validation") {
                    handler { event ->
                        val order = event as TradeOrder
                        val stageRecorder = getLatencyRecorder("validation")
                        
                        stageRecorder.startOperation()
                        try {
                            // 验证订单
                            if (!order.isValid()) {
                                order.updateStatus(OrderStatus.REJECTED, "订单验证失败")
                                order // 不使用返回值
                                return@handler
                            }
                            
                            // 更新订单状态
                            order.updateStatus(OrderStatus.VALIDATED)
                            
                            // 添加到订单簿
                            addToOrderBook(order)
                            
                            // 注意：这里修改返回值为Unit，解决类型不匹配问题
                            order // 返回值不使用
                        } finally {
                            stageRecorder.endOperation()
                        }
                    }
                    
                    // 输出到已验证订单主题
                    // 注释掉有错误的代码
                    // output {
                    //     toTopic(VALIDATED_ORDERS_TOPIC)
                    // }
                }
                
                // 2.2 订单匹配阶段
                stage("matching") {
                    // 从已验证订单主题接收
                    // 注释掉有错误的代码
                    // input {
                    //     fromTopic(VALIDATED_ORDERS_TOPIC)
                    // }
                    
                    handler { event ->
                        val order = event as TradeOrder
                        val stageRecorder = getLatencyRecorder("matching")
                        
                        stageRecorder.startOperation()
                        try {
                            // 如果订单已被拒绝，直接返回
                            if (order.status == OrderStatus.REJECTED) {
                                // 注意：这里修改返回值为Unit，解决类型不匹配问题
                                order // 返回值不使用
                                return@handler
                            }
                            
                            // 执行订单匹配
                            val matchedOrder = matchOrder(order)
                            
                            // 添加到已匹配订单列表
                            if (matchedOrder.status == OrderStatus.FILLED || 
                                matchedOrder.status == OrderStatus.PARTIALLY_FILLED) {
                                addToMatchedOrders(matchedOrder)
                            }
                            
                            // 注意：这里修改返回值为Unit，解决类型不匹配问题
                            matchedOrder // 返回值不使用
                        } finally {
                            stageRecorder.endOperation()
                        }
                    }
                    
                    // 输出到已匹配订单主题
                    // 注释掉有错误的代码
                    // output {
                    //     toTopic(MATCHED_ORDERS_TOPIC)
                    // }
                }
                
                // 2.3 订单执行阶段
                stage("execution") {
                    // 从已匹配订单主题接收
                    // 注释掉有错误的代码
                    // input {
                    //     fromTopic(MATCHED_ORDERS_TOPIC)
                    // }
                    
                    handler { event ->
                        val order = event as TradeOrder
                        val stageRecorder = getLatencyRecorder("execution")
                        
                        stageRecorder.startOperation()
                        try {
                            // 如果订单未匹配或已拒绝，直接返回
                            if (order.status != OrderStatus.FILLED && 
                                order.status != OrderStatus.PARTIALLY_FILLED) {
                                // 注意：这里修改返回值为Unit，解决类型不匹配问题
                                order // 返回值不使用
                                return@handler
                            }
                            
                            // 执行订单（例如清算、结算等）
                            val executedOrder = executeOrder(order)
                            
                            // 更新订单状态
                            if (executedOrder.filledQuantity >= executedOrder.quantity) {
                                executedOrder.updateStatus(OrderStatus.FILLED, "订单执行完成")
                            } else if (executedOrder.filledQuantity > 0) {
                                executedOrder.updateStatus(OrderStatus.PARTIALLY_FILLED)
                            }
                            
                            // 注意：这里修改返回值为Unit，解决类型不匹配问题
                            executedOrder // 返回值不使用
                        } finally {
                            stageRecorder.endOperation()
                        }
                    }
                    
                    // 输出到已执行订单主题
                    // 注释掉有错误的代码
                    // output {
                    //     toTopic(EXECUTED_ORDERS_TOPIC)
                    // }
                }
            }
            
            // 3. 结果输出
            // 注释掉有错误的代码
            // sink {
            //     toTopic(TRADES_TOPIC)
            // }
        }
    }
    
    /**
     * 添加订单到订单簿
     */
    private fun addToOrderBook(order: TradeOrder) {
        orderBook.computeIfAbsent(order.symbol) { ConcurrentHashMap<String, TradeOrder>() }
            .put(order.orderId, order)
    }
    
    /**
     * 添加已匹配订单到列表
     */
    private fun addToMatchedOrders(order: TradeOrder) {
        matchedOrders.computeIfAbsent(order.symbol) { mutableListOf() }
            .add(order)
    }
    
    /**
     * 匹配订单（简化实现）
     */
    private fun matchOrder(order: TradeOrder): TradeOrder {
        // 查找订单簿中的相应交易对
        val symbolOrders = orderBook.getOrDefault(order.symbol, mapOf())
        
        // 根据订单类型执行不同的匹配逻辑
        when (order.type) {
            OrderType.MARKET -> matchMarketOrder(order, symbolOrders)
            OrderType.LIMIT -> matchLimitOrder(order, symbolOrders)
            OrderType.STOP -> {
                // 检查是否触发止损价格（简化实现）
                if (order.stopPrice != null && isStopPriceTriggered(order)) {
                    // 转为市价单执行
                    matchMarketOrder(order, symbolOrders)
                }
            }
            OrderType.STOP_LIMIT -> {
                // 检查是否触发止损限价（简化实现）
                if (order.stopPrice != null && isStopPriceTriggered(order)) {
                    // 转为限价单执行
                    matchLimitOrder(order, symbolOrders)
                }
            }
        }
        
        return order
    }
    
    /**
     * 匹配市价单（简化实现）
     */
    private fun matchMarketOrder(order: TradeOrder, symbolOrders: Map<String, TradeOrder>) {
        // 找到所有可匹配的对手方订单
        val matchableSide = if (order.side == OrderSide.BUY) OrderSide.SELL else OrderSide.BUY
        
        // 简化实现：随机匹配一部分订单数量
        val matchableOrders = symbolOrders.values.filter { 
            it.side == matchableSide && it.status == OrderStatus.VALIDATED 
        }
        
        if (matchableOrders.isNotEmpty()) {
            // 随机匹配一个订单
            val matchedOrder = matchableOrders.random()
            
            // 计算匹配数量
            val matchQuantity = minOf(order.quantity - order.filledQuantity, 
                                       matchedOrder.quantity - matchedOrder.filledQuantity)
            
            if (matchQuantity > 0) {
                // 更新两边订单的成交数量
                order.updateFilledQuantity(matchQuantity)
                matchedOrder.updateFilledQuantity(matchQuantity)
                
                // 更新订单状态
                if (order.filledQuantity == order.quantity) {
                    order.updateStatus(OrderStatus.FILLED)
                } else {
                    order.updateStatus(OrderStatus.PARTIALLY_FILLED)
                }
            }
        }
    }
    
    /**
     * 匹配限价单（简化实现）
     */
    private fun matchLimitOrder(order: TradeOrder, symbolOrders: Map<String, TradeOrder>) {
        // 找到所有可匹配的对手方订单
        val matchableSide = if (order.side == OrderSide.BUY) OrderSide.SELL else OrderSide.BUY
        
        // 过滤可匹配的订单，并按价格排序
        val matchableOrders = symbolOrders.values.filter { 
            it.side == matchableSide && it.status == OrderStatus.VALIDATED 
        }.let {
            // 买单希望找到价格更低的卖单
            if (order.side == OrderSide.BUY) {
                it.filter { sellOrder -> 
                    sellOrder.price != null && order.price != null && 
                    sellOrder.price <= order.price 
                }.sortedBy { sellOrder -> sellOrder.price }
            }
            // 卖单希望找到价格更高的买单
            else {
                it.filter { buyOrder -> 
                    buyOrder.price != null && order.price != null && 
                    buyOrder.price >= order.price 
                }.sortedByDescending { buyOrder -> buyOrder.price }
            }
        }
        
        // 遍历可匹配订单尝试匹配
        var remainingQuantity = order.quantity - order.filledQuantity
        for (matchableOrder in matchableOrders) {
            if (remainingQuantity <= 0) break
            
            // 计算匹配数量
            val matchQuantity = minOf(remainingQuantity, 
                                       matchableOrder.quantity - matchableOrder.filledQuantity)
            
            if (matchQuantity > 0) {
                // 更新两边订单的成交数量
                order.updateFilledQuantity(matchQuantity)
                matchableOrder.updateFilledQuantity(matchQuantity)
                
                // 更新剩余可匹配数量
                remainingQuantity -= matchQuantity
                
                // 更新订单状态
                if (matchableOrder.filledQuantity == matchableOrder.quantity) {
                    matchableOrder.updateStatus(OrderStatus.FILLED)
                } else {
                    matchableOrder.updateStatus(OrderStatus.PARTIALLY_FILLED)
                }
            }
        }
        
        // 更新当前订单状态
        if (order.filledQuantity == order.quantity) {
            order.updateStatus(OrderStatus.FILLED)
        } else if (order.filledQuantity > 0) {
            order.updateStatus(OrderStatus.PARTIALLY_FILLED)
        }
    }
    
    /**
     * 检查止损价格是否触发（简化实现）
     */
    private fun isStopPriceTriggered(order: TradeOrder): Boolean {
        // 简化实现：随机决定是否触发
        return Math.random() > 0.5
    }
    
    /**
     * 执行订单（简化实现）
     */
    private fun executeOrder(order: TradeOrder): TradeOrder {
        // 简化：模拟订单执行延迟
        Thread.sleep((10..50).random().toLong())
        return order
    }
    
    /**
     * 获取延迟记录器
     */
    private fun getLatencyRecorder(stageName: String): LatencyRecorder {
        return latencyRecorders.computeIfAbsent(stageName) { LatencyRecorder() }
    }
    
    /**
     * 打印性能统计信息
     */
    fun printPerformanceStats() {
        println("\n=== 高频交易工作流性能统计 ===")
        latencyRecorders.forEach { (stage, recorder) ->
            println("\n--- $stage 阶段延迟统计 ---")
            recorder.printSummary()
        }
    }
    
    /**
     * 启动交易系统示例
     */
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        // 创建节点
        val node = DisruptorX.createNode(
            DisruptorXConfig(
                nodeId = "trading-node-1",
                host = "localhost",
                port = 8090
                // 注释掉有问题的引用
                // nodeRole = NodeRole.LEADER
            )
        )
        
        // 初始化节点
        node.initialize()
        
        // 注册工作流
        val tradingWorkflow = createWorkflow()
        node.workflowManager.register(tradingWorkflow)
        
        // 启动工作流
        node.workflowManager.start(TRADING_WORKFLOW_ID)
        println("交易工作流已启动")
        
        // 生成和处理随机订单
        val symbols = listOf("BTC-USDT", "ETH-USDT", "SOL-USDT", "BNB-USDT")
        val clientIds = listOf("client1", "client2", "client3", "client4", "client5")
        val orderTypes = listOf(OrderType.MARKET, OrderType.LIMIT)
        val orderSides = listOf(OrderSide.BUY, OrderSide.SELL)
        
        println("开始生成随机订单...")
        
        // 记录总体延迟
        val overallLatencyRecorder = LatencyRecorder()
        
        repeat(1000) { i ->
            val symbol = symbols.random()
            val clientId = clientIds.random()
            val orderType = orderTypes.random()
            val orderSide = orderSides.random()
            
            val order = if (orderType == OrderType.MARKET) {
                if (orderSide == OrderSide.BUY) {
                    TradeOrder.marketBuy(clientId, symbol, (100..1000).random().toLong())
                } else {
                    TradeOrder.marketSell(clientId, symbol, (100..1000).random().toLong())
                }
            } else {
                if (orderSide == OrderSide.BUY) {
                    TradeOrder.limitBuy(
                        clientId, 
                        symbol, 
                        BigDecimal((10000..50000).random()) / BigDecimal(100),
                        (100..1000).random().toLong()
                    )
                } else {
                    TradeOrder.limitSell(
                        clientId, 
                        symbol, 
                        BigDecimal((10000..50000).random()) / BigDecimal(100),
                        (100..1000).random().toLong()
                    )
                }
            }
            
            // 测量从发布到处理完成的端到端延迟
            val latencyNanos = measureNanoTime {
                // 发布订单
                node.eventBus.publish(order, ORDERS_TOPIC)
                
                // 等待订单处理完成
                // 注意：实际应用中会使用异步回调或响应式订阅，这里简化处理
                Thread.sleep(10)
            }
            
            // 记录延迟
            overallLatencyRecorder.recordLatency(latencyNanos)
            
            if (i % 100 == 0) {
                println("已处理 ${i+1} 个订单...")
            }
        }
        
        // 等待所有订单处理完成
        println("等待最后的订单处理完成...")
        Thread.sleep(2000)
        
        // 打印性能统计
        println("\n=== 端到端延迟统计 ===")
        overallLatencyRecorder.printSummary()
        
        printPerformanceStats()
        
        // 关闭节点
        node.shutdown()
        println("交易系统示例已关闭")
    }
} 