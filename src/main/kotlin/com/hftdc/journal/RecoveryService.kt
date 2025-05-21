package com.hftdc.journal

import com.hftdc.engine.OrderBookManager
import com.hftdc.model.Order
import com.hftdc.model.OrderBookSnapshot
import com.hftdc.model.OrderStatus
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 恢复服务 - 从日志和快照中恢复系统状态
 */
class RecoveryService(
    private val journalService: JournalService,
    private val orderBookManager: OrderBookManager
) {
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
        
        // 2. 然后应用快照之后的事件
        val now = Instant.now().toEpochMilli()
        val events = journalService.getEvents(
            instrumentId = instrumentId,
            startTime = lastSnapshotTime + 1,
            endTime = now,
            types = setOf(
                EventType.ORDER_SUBMITTED,
                EventType.ORDER_CANCELED,
                EventType.TRADE_CREATED
            )
        ).sortedBy { it.timestamp }
        
        logger.info { "恢复品种 $instrumentId 的 ${events.size} 个事件" }
        
        // 应用每个事件到订单簿
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        events.forEach { event ->
            try {
                when (event) {
                    is OrderSubmittedEvent -> {
                        // 只恢复active状态的订单
                        val order = event.order
                        if (order.status == OrderStatus.NEW || order.status == OrderStatus.PARTIALLY_FILLED) {
                            orderBook.addOrder(order)
                            logger.debug { "恢复订单: ${order.id}" }
                        }
                    }
                    is OrderCanceledEvent -> {
                        // 取消订单
                        orderBook.cancelOrder(event.orderId)
                        logger.debug { "取消订单: ${event.orderId}" }
                    }
                    // TradeCreatedEvent不需要直接应用，因为交易已经修改了相应的订单
                    else -> {
                        // 其他事件类型可能需要添加处理
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "应用事件 ${event.eventId} 失败" }
            }
        }
        
        logger.info { "品种 $instrumentId 恢复完成" }
    }
    
    /**
     * 获取系统中所有已知的品种列表
     */
    private fun getKnownInstrumentIds(): Set<String> {
        val result = mutableSetOf<String>()
        
        // TODO: 实现从配置或数据库获取所有已知品种的逻辑
        // 这里简化实现，返回一些预定义的品种
        result.addAll(listOf("BTC-USDT", "ETH-USDT", "LTC-USDT", "XRP-USDT"))
        
        return result
    }
} 