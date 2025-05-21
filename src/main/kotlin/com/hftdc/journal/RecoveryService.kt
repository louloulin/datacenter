package com.hftdc.journal

import com.hftdc.engine.OrderBookManager
import com.hftdc.model.*
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 恢复配置
 */
data class RecoveryConfig(
    // 是否包含快照前的事件（在恢复时考虑快照时间戳之前的事件）
    val includeEventsBeforeSnapshot: Boolean = false,
    // 快照前事件的时间窗口（毫秒）
    val eventsBeforeSnapshotTimeWindowMs: Long = 10000
)

/**
 * 恢复服务 - 负责从快照和日志事件中恢复系统状态
 */
class RecoveryService(
    private val journalService: JournalService,
    private val orderBookManager: OrderBookManager,
    private val config: RecoveryConfig = RecoveryConfig()
) {
    // 存储订单ID与品种ID的映射关系，用于处理取消订单事件
    private val orderIdToInstrumentId = ConcurrentHashMap<Long, String>()
    
    // 记录恢复的统计信息
    private var lastRecoveryStats: RecoveryStats? = null

    /**
     * 执行系统恢复
     */
    fun recover(): RecoveryStats {
        val startTime = Instant.now()
        logger.info { "开始系统恢复..." }

        val instruments = journalService.getInstrumentsWithSnapshots()
        logger.info { "发现 ${instruments.size} 个有快照的交易品种" }

        var totalOrderBooks = 0
        var totalOrders = 0
        var totalEvents = 0

        // 为每个交易品种执行恢复
        instruments.forEach { instrumentId ->
            try {
                val stats = recoverInstrument(instrumentId)
                totalOrderBooks++
                totalOrders += stats.ordersRecovered
                totalEvents += stats.eventsApplied
                
                logger.info { "品种 $instrumentId 恢复完成: 恢复订单 ${stats.ordersRecovered} 个, 应用事件 ${stats.eventsApplied} 个" }
            } catch (e: Exception) {
                logger.error(e) { "恢复品种 $instrumentId 失败" }
            }
        }

        val elapsedMs = Instant.now().toEpochMilli() - startTime.toEpochMilli()
        val stats = RecoveryStats(
            timestamp = startTime.toEpochMilli(),
            elapsedTimeMs = elapsedMs,
            instrumentsRecovered = totalOrderBooks,
            ordersRecovered = totalOrders,
            eventsApplied = totalEvents
        )
        
        lastRecoveryStats = stats
        logger.info { "系统恢复完成, 耗时 ${elapsedMs}ms: 恢复订单簿 $totalOrderBooks 个, 订单 $totalOrders 个, 应用事件 $totalEvents 个" }
        
        return stats
    }

    /**
     * 恢复单个交易品种
     */
    private fun recoverInstrument(instrumentId: String): InstrumentRecoveryStats {
        logger.info { "开始恢复品种: $instrumentId" }
        
        // 获取最新快照
        val snapshot = journalService.getLatestSnapshot(instrumentId)
            ?: throw IllegalStateException("品种 $instrumentId 没有可用快照")
        
        // 从快照获取时间戳
        val snapshotTimestamp = snapshot.timestamp
        logger.info { "找到品种 $instrumentId 的最新快照, 时间戳: $snapshotTimestamp" }
        
        // 获取快照后的事件
        val eventsSinceSnapshot = journalService.getEventsSince(instrumentId, snapshotTimestamp)
        logger.info { "获取到快照后的事件: ${eventsSinceSnapshot.size} 个" }
        
        // 如果配置了，获取快照前指定时间窗口内的事件
        val eventsBeforeSnapshot = if (config.includeEventsBeforeSnapshot) {
            val fromTime = snapshotTimestamp - config.eventsBeforeSnapshotTimeWindowMs
            journalService.getEventsBetween(instrumentId, fromTime, snapshotTimestamp)
        } else {
            emptyList()
        }
        logger.info { "获取到快照前的事件: ${eventsBeforeSnapshot.size} 个" }
        
        // 基于快照恢复订单簿
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        val ordersRecovered = restoreFromSnapshot(orderBook, snapshot.snapshot)
        logger.info { "从快照恢复订单: $ordersRecovered 个" }
        
        // 应用快照前的事件（如果有配置）
        val beforeEventsApplied = if (eventsBeforeSnapshot.isNotEmpty()) {
            applyEvents(orderBook, eventsBeforeSnapshot)
        } else 0
        
        // 应用快照后的事件
        val afterEventsApplied = applyEvents(orderBook, eventsSinceSnapshot)
        
        return InstrumentRecoveryStats(
            instrumentId = instrumentId,
            snapshotTimestamp = snapshotTimestamp,
            ordersRecovered = ordersRecovered,
            eventsBeforeSnapshotApplied = beforeEventsApplied,
            eventsAfterSnapshotApplied = afterEventsApplied,
            eventsApplied = beforeEventsApplied + afterEventsApplied
        )
    }

    /**
     * 从快照恢复订单簿状态
     */
    private fun restoreFromSnapshot(orderBook: OrderBook, snapshot: OrderBookSnapshot): Int {
        var restoredOrders = 0
        
        // 恢复买单
        snapshot.bids.forEach { level ->
            val placeholderOrder = createPlaceholderOrder(
                orderBook.instrumentId,
                level.price,
                level.quantity,
                OrderSide.BUY
            )
            orderBook.addOrder(placeholderOrder)
            restoredOrders++
        }
        
        // 恢复卖单
        snapshot.asks.forEach { level ->
            val placeholderOrder = createPlaceholderOrder(
                orderBook.instrumentId,
                level.price,
                level.quantity,
                OrderSide.SELL
            )
            orderBook.addOrder(placeholderOrder)
            restoredOrders++
        }
        
        return restoredOrders
    }

    /**
     * 创建用于恢复的占位订单
     */
    private fun createPlaceholderOrder(
        instrumentId: String,
        price: Long,
        quantity: Long,
        side: OrderSide
    ): Order {
        return Order(
            id = -System.nanoTime(), // 使用负数ID表示这是恢复时创建的占位订单
            userId = -1, // 系统用户ID
            instrumentId = instrumentId,
            price = price,
            quantity = quantity,
            remainingQuantity = quantity,
            side = side,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = Instant.now().toEpochMilli()
        )
    }

    /**
     * 应用事件到订单簿
     */
    private fun applyEvents(orderBook: OrderBook, events: List<JournalEvent>): Int {
        var appliedEvents = 0
        
        events.forEach { event ->
            try {
                when (event) {
                    is OrderSubmittedEvent -> {
                        if (event.order.status != OrderStatus.REJECTED) {
                            orderBook.addOrder(event.order)
                        }
                        appliedEvents++
                    }
                    is OrderCanceledEvent -> {
                        orderBook.cancelOrder(event.orderId)
                        appliedEvents++
                    }
                    is TradeCreatedEvent -> {
                        // 交易已经通过OrderSubmittedEvent处理了订单更新，这里可以做额外处理
                        appliedEvents++
                    }
                    else -> {
                        logger.warn { "未知的事件类型: ${event.javaClass.simpleName}" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "应用事件失败: $event" }
            }
        }
        
        return appliedEvents
    }

    /**
     * 获取最近一次恢复的统计信息
     */
    fun getLastRecoveryStats(): RecoveryStats? = lastRecoveryStats
}

/**
 * 恢复统计信息
 */
data class RecoveryStats(
    val timestamp: Long,
    val elapsedTimeMs: Long,
    val instrumentsRecovered: Int,
    val ordersRecovered: Int,
    val eventsApplied: Int
)

/**
 * 单个品种的恢复统计信息
 */
data class InstrumentRecoveryStats(
    val instrumentId: String,
    val snapshotTimestamp: Long,
    val ordersRecovered: Int,
    val eventsBeforeSnapshotApplied: Int,
    val eventsAfterSnapshotApplied: Int,
    val eventsApplied: Int
) 