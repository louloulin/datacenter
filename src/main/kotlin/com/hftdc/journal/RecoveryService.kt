package com.hftdc.journal

import com.hftdc.engine.OrderBookManager
import com.hftdc.model.Order
import com.hftdc.model.OrderBookSnapshot
import com.hftdc.model.OrderStatus
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 恢复服务 - 从日志和快照中恢复系统状态
 */
class RecoveryService(
    private val journalService: JournalService,
    private val orderBookManager: OrderBookManager,
    private val config: RecoveryConfig = RecoveryConfig()
) {
    // 存储订单ID与品种ID的映射关系，用于处理取消订单事件
    private val orderIdToInstrumentId = ConcurrentHashMap<Long, String>()
    
    /**
     * 执行恢复操作
     */
    fun recover() {
        logger.info { "开始恢复系统状态..." }
        
        // 获取所有已知的品种列表
        val instrumentIds = getKnownInstrumentIds()
        
        // 对每个品种执行恢复
        instrumentIds.forEach { instrumentId ->
            recoverInstrument(instrumentId)
        }
        
        logger.info { "系统状态恢复完成" }
    }
    
    /**
     * 恢复单个品种的状态
     */
    private fun recoverInstrument(instrumentId: String) {
        logger.info { "恢复品种状态: $instrumentId" }
        
        // 1. 首先尝试从最新快照恢复
        val snapshot = journalService.getLatestSnapshot(instrumentId)
        var lastSnapshotTime = 0L
        
        if (snapshot != null) {
            // 恢复订单簿状态
            val orderBook = orderBookManager.getOrderBook(instrumentId)
            orderBook.restoreFromSnapshot(snapshot.snapshot)
            lastSnapshotTime = snapshot.timestamp
            
            logger.info { 
                "从快照恢复品种 $instrumentId, 快照时间: ${Instant.ofEpochMilli(lastSnapshotTime)}, " +
                "买单数: ${snapshot.snapshot.bids.size}, 卖单数: ${snapshot.snapshot.asks.size}" 
            }
        } else {
            logger.info { "品种 $instrumentId 没有可用的快照" }
        }
        
        // 2. 然后应用快照前的事件（如果配置允许）
        if (config.includeEventsBeforeSnapshot && lastSnapshotTime > 0) {
            val eventsBeforeSnapshot = journalService.getEvents(
                instrumentId = instrumentId,
                startTime = lastSnapshotTime - config.eventsBeforeSnapshotTimeWindowMs,
                endTime = lastSnapshotTime - 1, // 排除快照时间点
                types = setOf(
                    EventType.ORDER_SUBMITTED,
                    EventType.ORDER_CANCELED,
                    EventType.TRADE_CREATED
                )
            ).sortedBy { it.timestamp }
            
            logger.info { "恢复快照前的 ${eventsBeforeSnapshot.size} 个事件" }
            applyEventsToOrderBook(instrumentId, eventsBeforeSnapshot)
        }
        
        // 3. 然后应用快照之后的事件
        val now = Instant.now().toEpochMilli()
        println("RecoveryService: 获取 $instrumentId 的事件, 时间范围: $lastSnapshotTime - $now")
        
        val eventsAfterSnapshot = journalService.getEvents(
            instrumentId = instrumentId,
            startTime = lastSnapshotTime,  // 包含快照时间点，确保不漏过任何事件
            endTime = now,
            types = setOf(
                EventType.ORDER_SUBMITTED,
                EventType.ORDER_CANCELED,
                EventType.TRADE_CREATED
            )
        ).sortedBy { it.timestamp }
        
        logger.info { "恢复快照后的 ${eventsAfterSnapshot.size} 个事件" }
        applyEventsToOrderBook(instrumentId, eventsAfterSnapshot)
        
        // 获取最终恢复的订单簿状态
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        logger.info { "品种 $instrumentId 恢复完成, 订单数量: ${orderBook.getPendingOrdersCount()}, 总订单: ${orderBook.getAllOrders().size}" }
    }
    
    /**
     * 应用事件到订单簿
     */
    private fun applyEventsToOrderBook(instrumentId: String, events: List<JournalEvent>) {
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        
        // 输出要处理的事件列表
        events.forEach { event ->
            println("RecoveryService: 处理事件: type=${event.type}, timestamp=${event.timestamp}, eventId=${event.eventId}")
            if (event is OrderSubmittedEvent) {
                println("  订单ID: ${event.order.id}, 价格: ${event.order.price}, instrumentId: ${event.order.instrumentId}")
            }
        }
        
        // 首先处理所有订单提交事件，建立订单ID到品种ID的映射
        events.forEach { event ->
            if (event is OrderSubmittedEvent) {
                orderIdToInstrumentId[event.order.id] = event.order.instrumentId
            }
        }
        
        // 应用每个事件到订单簿
        events.forEach { event ->
            try {
                when (event) {
                    is OrderSubmittedEvent -> {
                        // 只处理需要恢复的订单状态
                        val order = event.order
                        if (order.status == OrderStatus.NEW || order.status == OrderStatus.PARTIALLY_FILLED) {
                            // 检查订单是否已经在订单簿中 (可能是占位订单)
                            val existingOrder = orderBook.getOrder(order.id)
                            if (existingOrder == null || existingOrder.isPlaceholder) {
                                // 新增订单或替换占位订单
                                orderBook.addOrder(order)
                                logger.debug { "恢复订单: ${order.id}, 价格: ${order.price}" }
                            }
                        }
                    }
                    is OrderCanceledEvent -> {
                        // 取消订单
                        val orderId = event.orderId
                        
                        // 检查此订单ID是否属于当前处理的品种
                        // 首先尝试从事件本身获取instrumentId，如果没有则从映射中查找
                        val eventInstrumentId = event.instrumentId ?: orderIdToInstrumentId[orderId]
                        if (eventInstrumentId == instrumentId) {
                            val existingOrder = orderBook.getOrder(orderId)
                            
                            if (existingOrder != null && existingOrder.canBeCanceled()) {
                                val canceledOrder = orderBook.cancelOrder(orderId)
                                if (canceledOrder != null) {
                                    logger.debug { "取消订单: $orderId, 状态: ${canceledOrder.status}" }
                                } else {
                                    logger.debug { "无法取消订单: $orderId" }
                                }
                            }
                        }
                    }
                    is TradeCreatedEvent -> {
                        // 对于交易事件，我们需要确保相关订单的状态被正确更新
                        val trade = event.trade
                        
                        // 获取交易相关的买单和卖单
                        val buyOrder = orderBook.getOrder(trade.buyOrderId)
                        val sellOrder = orderBook.getOrder(trade.sellOrderId)
                        
                        // 如果订单存在，确保其状态和剩余数量反映了此交易
                        // 这里不需要做额外处理，因为订单状态应该已经在订单执行事件中被更新了
                        logger.debug { "处理交易: ${trade.id}, 买单: ${trade.buyOrderId}, 卖单: ${trade.sellOrderId}" }
                    }
                    else -> {
                        // 其他事件类型，暂不处理
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "应用事件 ${event.eventId} 失败" }
            }
        }
    }
    
    /**
     * 获取系统中所有已知的品种列表
     */
    private fun getKnownInstrumentIds(): Set<String> {
        val result = mutableSetOf<String>()
        
        // 获取所有已经有快照的品种
        val snapshotInstruments = journalService.getSnapshotInstrumentIds()
        result.addAll(snapshotInstruments)
        
        // 添加一些预定义的品种
        result.addAll(listOf("BTC-USDT", "ETH-USDT", "LTC-USDT", "XRP-USDT", "DOT-USDT", "DOGE-USDT"))
        
        return result
    }
}

/**
 * 恢复服务配置
 */
data class RecoveryConfig(
    // 是否包含快照前的事件
    val includeEventsBeforeSnapshot: Boolean = false,
    
    // 快照前事件的时间窗口（毫秒）
    val eventsBeforeSnapshotTimeWindowMs: Long = 5000 // 默认5秒
) 