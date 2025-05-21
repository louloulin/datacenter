package com.hftdc.engine

import com.hftdc.journal.JournalEvent
import com.hftdc.journal.JournalService
import com.hftdc.journal.OrderSubmittedEvent
import com.hftdc.journal.TradeCreatedEvent
import com.hftdc.metrics.OrderMetricsHandler
import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.RingBuffer
import com.lmax.disruptor.WaitStrategy
import com.lmax.disruptor.YieldingWaitStrategy
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import com.lmax.disruptor.util.DaemonThreadFactory
import com.hftdc.model.Order
import com.hftdc.model.OrderStatus
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * 订单事件 - Disruptor环形缓冲区中的元素
 */
data class OrderEvent(
    var order: Order? = null,
    var timestamp: Long = 0,
    var trades: List<com.hftdc.model.Trade> = emptyList()
) {
    fun clear() {
        order = null
        timestamp = 0
        trades = emptyList()
    }
}

/**
 * 订单事件工厂 - 创建订单事件的工厂
 */
class OrderEventFactory : EventFactory<OrderEvent> {
    override fun newInstance(): OrderEvent = OrderEvent()
}

/**
 * 风控检查处理器 - 第一阶段处理，检查订单风控
 */
class RiskCheckHandler(
    private val riskManager: com.hftdc.risk.RiskManager,
    private val metricsHandler: OrderMetricsHandler? = null
) : EventHandler<OrderEvent> {
    override fun onEvent(event: OrderEvent, sequence: Long, endOfBatch: Boolean) {
        val order = event.order ?: return
        
        // 记录订单接收指标
        metricsHandler?.recordOrderReceived(order)
        
        // 在这里实现风控逻辑
        logger.debug { "风控检查订单: $order" }
        
        // 执行风险检查
        val result = riskManager.checkOrder(order)
        
        // 如果订单未通过风险检查，将其标记为拒绝
        if (!result.passed) {
            logger.warn { "订单 ${order.id} 被风险控制拒绝: ${result.reason}" }
            
            // 更新订单状态为拒绝
            val rejectedOrder = order.copy(status = OrderStatus.REJECTED)
            event.order = rejectedOrder
            
            // 记录订单完成指标
            metricsHandler?.recordOrderCompleted(rejectedOrder)
            
            // TODO: 发送拒绝通知给用户
        } else {
            // 记录用户下单事件，更新速率限制
            riskManager.recordOrderEvent(order.userId)
        }
        
        // 更新事件时间戳，用于性能指标收集
        event.timestamp = Instant.now().toEpochMilli()
    }
}

/**
 * 订单匹配处理器 - 第二阶段处理，执行订单匹配
 */
class OrderMatchHandler(
    private val orderBookManager: OrderBookManager,
    private val marketDataProcessor: com.hftdc.market.MarketDataProcessor? = null,
    private val metricsHandler: OrderMetricsHandler? = null
) : EventHandler<OrderEvent> {
    override fun onEvent(event: OrderEvent, sequence: Long, endOfBatch: Boolean) {
        val order = event.order ?: return
        
        // 在这里实现订单匹配逻辑
        logger.debug { "匹配订单: $order" }
        
        // 获取订单簿并执行订单匹配
        val orderBook = orderBookManager.getOrderBook(order.instrumentId)
        val trades = orderBook.addOrder(order)
        
        // 存储交易到事件对象，以便下一阶段处理
        event.trades = trades
        
        // 处理交易结果
        if (trades.isNotEmpty()) {
            logger.info { "订单 ${order.id} 产生了 ${trades.size} 笔交易" }
            
            // 记录订单执行指标
            metricsHandler?.recordOrderExecuted(order, trades)
            
            // 发布交易到市场数据处理器
            trades.forEach { trade ->
                marketDataProcessor?.onTrade(trade)
            }
        } else {
            logger.debug { "订单 ${order.id} 未匹配，已加入订单簿" }
        }
        
        // 如果订单已完全成交或已取消，记录完成指标
        if (order.status == OrderStatus.FILLED || order.status == OrderStatus.CANCELED) {
            metricsHandler?.recordOrderCompleted(order)
        }
        
        // 更新事件时间戳，用于性能指标收集
        event.timestamp = Instant.now().toEpochMilli()
    }
}

/**
 * 日志记录处理器 - 第三阶段处理，记录订单处理日志
 */
class JournalHandler(
    private val journalService: JournalService?,
    private val metricsHandler: OrderMetricsHandler? = null
) : EventHandler<OrderEvent> {
    // 事件ID计数器
    private val eventIdCounter = AtomicLong(0)
    
    override fun onEvent(event: OrderEvent, sequence: Long, endOfBatch: Boolean) {
        val order = event.order ?: return
        
        if (journalService != null) {
            try {
                // 记录订单提交事件
                val orderSubmittedEvent = OrderSubmittedEvent(
                    eventId = eventIdCounter.incrementAndGet(),
                    timestamp = event.timestamp,
                    order = order
                )
                journalService.journal(orderSubmittedEvent)
                
                // 记录交易事件
                event.trades.forEach { trade ->
                    val tradeCreatedEvent = TradeCreatedEvent(
                        eventId = eventIdCounter.incrementAndGet(),
                        timestamp = event.timestamp,
                        trade = trade
                    )
                    journalService.journal(tradeCreatedEvent)
                }
                
                logger.debug { "已记录订单日志: 订单ID=${order.id}, 交易数量=${event.trades.size}" }
            } catch (e: Exception) {
                logger.error(e) { "记录订单日志失败: ${order.id}" }
            }
        } else {
            logger.debug { "跳过日志记录，未配置日志服务" }
        }
        
        // 周期性清理过期的指标记录
        if (sequence % 1000 == 0) {
            metricsHandler?.cleanupStaleRecords()
        }
        
        // 清空事件，以便重用
        event.clear()
    }
}

/**
 * 订单处理器 - 使用Disruptor处理订单
 */
class OrderProcessor(
    bufferSize: Int = 4096,
    private val waitStrategy: WaitStrategy = YieldingWaitStrategy(),
    private val producerType: ProducerType = ProducerType.MULTI,
    private val orderBookManager: OrderBookManager,
    private val marketDataProcessor: com.hftdc.market.MarketDataProcessor? = null,
    private val riskManager: com.hftdc.risk.RiskManager? = null,
    private val journalService: JournalService? = null,
    private val orderMetricsHandler: OrderMetricsHandler? = null
) {
    private val disruptor: Disruptor<OrderEvent>
    private val ringBuffer: RingBuffer<OrderEvent>
    
    init {
        // 创建Disruptor
        disruptor = Disruptor(
            OrderEventFactory(),
            bufferSize,
            DaemonThreadFactory.INSTANCE,
            producerType,
            waitStrategy
        )
        
        // 配置处理链
        val riskHandler = if (riskManager != null) {
            RiskCheckHandler(riskManager, orderMetricsHandler)
        } else {
            object : EventHandler<OrderEvent> {
                override fun onEvent(event: OrderEvent, sequence: Long, endOfBatch: Boolean) {
                    // 无风险管理器时，跳过风险检查
                    logger.debug { "跳过风险检查，未配置风险管理器" }
                    
                    // 记录订单接收指标
                    event.order?.let { order ->
                        orderMetricsHandler?.recordOrderReceived(order)
                    }
                }
            }
        }
        
        // 创建订单匹配处理器和日志处理器
        val matchHandler = OrderMatchHandler(orderBookManager, marketDataProcessor, orderMetricsHandler)
        val journalHandler = JournalHandler(journalService, orderMetricsHandler)
        
        // 设置处理链
        disruptor.handleEventsWith(riskHandler)
            .then(matchHandler)
            .then(journalHandler)
        
        // 启动Disruptor
        ringBuffer = disruptor.start()
        
        logger.info { "订单处理器已启动，缓冲区大小: $bufferSize" }
    }
    
    /**
     * 提交订单到处理队列
     */
    fun submitOrder(order: Order) {
        val sequence = ringBuffer.next()
        try {
            val event = ringBuffer.get(sequence)
            event.order = order
            event.timestamp = Instant.now().toEpochMilli()
        } finally {
            ringBuffer.publish(sequence)
        }
    }
    
    /**
     * 关闭处理器
     */
    fun shutdown() {
        logger.info { "关闭订单处理器..." }
        disruptor.shutdown()
    }
} 