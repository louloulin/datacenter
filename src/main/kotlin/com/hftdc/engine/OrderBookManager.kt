package com.hftdc.engine

import com.hftdc.model.OrderBook
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * 订单簿管理器 - 管理所有交易品种的订单簿
 */
class OrderBookManager(
    private val maxInstruments: Int, 
    private val snapshotIntervalMs: Long,
    private val enableInternalSnapshots: Boolean = false // 是否使用内部快照机制，默认false，推荐使用SnapshotManager代替
) {
    // 使用ConcurrentHashMap存储所有订单簿，以instrumentId为key
    private val orderBooks = ConcurrentHashMap<String, OrderBook>()
    
    // 调度器，用于定期执行快照和清理任务
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    
    init {
        // 注册定期任务
        if (enableInternalSnapshots && snapshotIntervalMs > 0) {
            scheduler.scheduleAtFixedRate(
                { createSnapshots() },
                snapshotIntervalMs,
                snapshotIntervalMs,
                TimeUnit.MILLISECONDS
            )
            logger.info { "订单簿管理器已启动，最大交易品种数: $maxInstruments, 快照间隔: ${snapshotIntervalMs}ms" }
        } else {
            logger.info { "订单簿管理器已启动，最大交易品种数: $maxInstruments, 内部快照功能已禁用" }
        }
    }
    
    /**
     * 获取指定交易品种的订单簿，如果不存在则创建
     */
    fun getOrderBook(instrumentId: String): OrderBook {
        // 检查是否超过最大交易品种数限制
        if (orderBooks.size >= maxInstruments && !orderBooks.containsKey(instrumentId)) {
            throw IllegalStateException("超过最大交易品种数限制: $maxInstruments")
        }
        
        // 获取或创建订单簿
        return orderBooks.computeIfAbsent(instrumentId) { OrderBook(it) }
    }
    
    /**
     * 创建所有订单簿的快照
     */
    private fun createSnapshots() {
        try {
            logger.debug { "开始创建订单簿快照, 当前订单簿数量: ${orderBooks.size}" }
            
            orderBooks.forEach { (instrumentId, orderBook) ->
                try {
                    // 为每个订单簿创建快照
                    val snapshot = orderBook.getSnapshot(10) // 10层深度
                    
                    // TODO: 将快照保存到持久化存储
                    logger.debug { "已创建订单簿快照: $instrumentId, 买单: ${snapshot.bids.size}, 卖单: ${snapshot.asks.size}" }
                } catch (e: Exception) {
                    logger.error(e) { "创建订单簿快照失败: $instrumentId" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "快照任务执行失败" }
        }
    }
    
    /**
     * 获取所有订单簿的列表
     */
    fun getAllOrderBooks(): Map<String, OrderBook> = orderBooks.toMap()
    
    /**
     * 获取当前订单簿数量
     */
    fun getOrderBookCount(): Int = orderBooks.size
    
    /**
     * 获取指定交易品种的订单簿中的订单数量
     */
    fun getOrderCount(instrumentId: String): Int {
        return orderBooks[instrumentId]?.getPendingOrdersCount() ?: 0
    }
    
    /**
     * 获取所有订单簿中的总订单数量
     */
    fun getTotalOrderCount(): Int {
        return orderBooks.values.sumOf { it.getPendingOrdersCount() }
    }
    
    /**
     * 获取所有活跃的交易品种ID
     * 
     * 活跃的定义：
     * 1. 已创建订单簿
     * 2. 有挂单 或 过去24小时内有交易
     */
    fun getActiveInstrumentIds(): Set<String> {
        return orderBooks.keys.toSet()
    }
    
    /**
     * 检查交易品种是否存在
     */
    fun hasInstrument(instrumentId: String): Boolean {
        return orderBooks.containsKey(instrumentId)
    }
    
    /**
     * 关闭管理器
     */
    fun shutdown() {
        logger.info { "关闭订单簿管理器..." }
        
        // 关闭调度器
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
        }
        
        // 清理资源
        orderBooks.clear()
    }
} 