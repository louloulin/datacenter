package com.hftdc.market

import com.hftdc.engine.OrderBookManager
import com.hftdc.model.OrderBookLevel
import com.hftdc.model.OrderBookSnapshot
import com.hftdc.model.Trade
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * 市场数据处理器 - 负责生成和发布市场数据
 */
class MarketDataProcessor(
    private val orderBookManager: OrderBookManager,
    private val snapshotIntervalMs: Long = 1000 // 默认1秒生成一次完整快照
) {
    // 调度器，用于定期任务
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    
    // 市场数据订阅者 - 按交易品种和频率进行分组
    private val subscribers = ConcurrentHashMap<String, ConcurrentHashMap<MarketDataFrequency, CopyOnWriteArrayList<MarketDataSubscriber>>>()
    
    // 最后发布的交易ID
    private val lastPublishedTradeId = ConcurrentHashMap<String, AtomicLong>()
    
    // 市场统计数据
    private val marketStatistics = ConcurrentHashMap<String, MutableMap<StatisticsPeriod, MarketStatisticsBuilder>>()
    
    init {
        // 启动定期任务
        startPeriodicTasks()
        
        logger.info { "市场数据处理器已启动" }
    }
    
    /**
     * 启动定期任务
     */
    private fun startPeriodicTasks() {
        // 定期发布快照
        if (snapshotIntervalMs > 0) {
            scheduler.scheduleAtFixedRate(
                { publishSnapshots() },
                0,
                snapshotIntervalMs,
                TimeUnit.MILLISECONDS
            )
        }
        
        // 定期计算并发布统计数据
        scheduler.scheduleAtFixedRate(
            { publishStatistics() },
            0,
            60_000, // 每分钟
            TimeUnit.MILLISECONDS
        )
    }
    
    /**
     * 发布所有订阅品种的市场数据快照
     */
    private fun publishSnapshots() {
        try {
            subscribers.keys.forEach { instrumentId ->
                val orderBook = orderBookManager.getOrderBook(instrumentId)
                val snapshot = orderBook.getSnapshot(10) // 默认10层深度
                publishMarketDataUpdate(createMarketDataUpdate(snapshot, MarketDataUpdateType.SNAPSHOT))
            }
        } catch (e: Exception) {
            logger.error(e) { "发布市场数据快照时发生错误" }
        }
    }
    
    /**
     * 发布统计数据
     */
    private fun publishStatistics() {
        try {
            marketStatistics.forEach { (instrumentId, periodStats) ->
                periodStats.forEach { (period, builder) ->
                    if (builder.canBuild()) {
                        val statistics = builder.build()
                        // 发布统计数据
                        val frequencyMap = subscribers[instrumentId] ?: return@forEach
                        frequencyMap.forEach { (_, subs) ->
                            subs.forEach { subscriber ->
                                if (subscriber.subscription.includeStatistics) {
                                    subscriber.onStatistics(statistics)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "发布统计数据时发生错误" }
        }
    }
    
    /**
     * 处理新的交易，更新统计数据并发布市场数据
     */
    fun onTrade(trade: Trade) {
        try {
            val instrumentId = trade.instrumentId
            
            // 更新统计数据
            updateStatistics(trade)
            
            // 获取最新订单簿状态
            val orderBook = orderBookManager.getOrderBook(instrumentId)
            val snapshot = orderBook.getSnapshot(10)
            
            // 创建并发布市场数据更新
            val update = createMarketDataUpdate(
                snapshot,
                MarketDataUpdateType.TRADE,
                trade.price,
                trade.quantity,
                trade.timestamp
            )
            
            publishMarketDataUpdate(update)
            
            // 更新最后发布的交易ID
            lastPublishedTradeId.computeIfAbsent(instrumentId) { AtomicLong(0) }
                .set(trade.id)
                
            logger.debug { "处理交易并发布市场数据: $trade" }
        } catch (e: Exception) {
            logger.error(e) { "处理交易时发生错误: $trade" }
        }
    }
    
    /**
     * 订阅市场数据
     */
    fun subscribe(subscription: MarketDataSubscription, subscriber: MarketDataSubscriber): Boolean {
        try {
            val instrumentId = subscription.instrumentId
            val frequency = subscription.frequency
            
            // 添加到订阅者列表
            subscribers.computeIfAbsent(instrumentId) { ConcurrentHashMap() }
                .computeIfAbsent(frequency) { CopyOnWriteArrayList() }
                .add(subscriber)
            
            logger.info { "新增市场数据订阅: $instrumentId, 频率: $frequency" }
            
            // 立即发送当前快照
            val orderBook = orderBookManager.getOrderBook(instrumentId)
            val snapshot = orderBook.getSnapshot(subscription.depth)
            subscriber.onMarketDataUpdate(createMarketDataUpdate(snapshot, MarketDataUpdateType.SNAPSHOT))
            
            return true
        } catch (e: Exception) {
            logger.error(e) { "订阅市场数据失败: $subscription" }
            return false
        }
    }
    
    /**
     * 取消订阅市场数据
     */
    fun unsubscribe(subscription: MarketDataSubscription, subscriber: MarketDataSubscriber): Boolean {
        try {
            val instrumentId = subscription.instrumentId
            val frequency = subscription.frequency
            
            subscribers[instrumentId]?.get(frequency)?.remove(subscriber)
            
            // 清理空列表
            if (subscribers[instrumentId]?.get(frequency)?.isEmpty() == true) {
                subscribers[instrumentId]?.remove(frequency)
            }
            
            if (subscribers[instrumentId]?.isEmpty() == true) {
                subscribers.remove(instrumentId)
            }
            
            logger.info { "取消市场数据订阅: $instrumentId, 频率: $frequency" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "取消订阅市场数据失败: $subscription" }
            return false
        }
    }
    
    /**
     * 发布市场数据更新
     */
    private fun publishMarketDataUpdate(update: MarketDataUpdate) {
        val instrumentId = update.instrumentId
        val frequencyMap = subscribers[instrumentId] ?: return
        
        frequencyMap.forEach { (frequency, subscribers) ->
            // 根据频率过滤实时发送
            val shouldPublish = when (frequency) {
                MarketDataFrequency.REALTIME -> true
                else -> {
                    // 按照其他频率，需要基于时间戳判断是否发送
                    // 这里简化实现，实际应该基于上次发送时间判断
                    update.updateType == MarketDataUpdateType.SNAPSHOT
                }
            }
            
            if (shouldPublish) {
                subscribers.forEach { it.onMarketDataUpdate(update) }
            }
        }
    }
    
    /**
     * 创建市场数据更新
     */
    private fun createMarketDataUpdate(
        snapshot: OrderBookSnapshot,
        updateType: MarketDataUpdateType,
        lastTradePrice: Long? = null,
        lastTradeQuantity: Long? = null,
        lastTradeTimestamp: Long? = null
    ): MarketDataUpdate {
        return MarketDataUpdate(
            instrumentId = snapshot.instrumentId,
            timestamp = Instant.now().toEpochMilli(),
            bids = snapshot.bids,
            asks = snapshot.asks,
            lastTradePrice = lastTradePrice,
            lastTradeQuantity = lastTradeQuantity,
            lastTradeTimestamp = lastTradeTimestamp,
            updateType = updateType
        )
    }
    
    /**
     * 更新统计数据
     */
    private fun updateStatistics(trade: Trade) {
        val instrumentId = trade.instrumentId
        val statsMap = marketStatistics.computeIfAbsent(instrumentId) { mutableMapOf() }
        
        // 更新各个周期的统计数据
        StatisticsPeriod.values().forEach { period ->
            val builder = statsMap.computeIfAbsent(period) { 
                MarketStatisticsBuilder(instrumentId, period)
            }
            builder.addTrade(trade)
        }
    }
    
    /**
     * 关闭处理器
     */
    fun shutdown() {
        logger.info { "关闭市场数据处理器..." }
        
        // 关闭调度器
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        // 清理资源
        subscribers.clear()
        marketStatistics.clear()
    }
}

/**
 * 市场数据订阅者接口
 */
interface MarketDataSubscriber {
    val subscription: MarketDataSubscription
    
    fun onMarketDataUpdate(update: MarketDataUpdate)
    fun onStatistics(statistics: MarketStatistics)
}

/**
 * 市场统计数据构建器
 */
class MarketStatisticsBuilder(
    private val instrumentId: String,
    private val period: StatisticsPeriod
) {
    private var openPrice: Long? = null
    private var highPrice: Long? = null
    private var lowPrice: Long? = null
    private var closePrice: Long? = null
    private var volume: Long = 0
    private var tradeCount: Int = 0
    private var volumePrice: Long = 0 // 用于计算VWAP
    private var periodStartTime: Long = alignToPeriodStart(Instant.now().toEpochMilli())
    
    /**
     * 添加交易到统计数据
     */
    fun addTrade(trade: Trade) {
        // 如果交易不在当前周期内，重置统计数据
        if (trade.timestamp > periodStartTime + getPeriodMillis()) {
            reset()
            periodStartTime = alignToPeriodStart(trade.timestamp)
        }
        
        // 更新统计数据
        if (openPrice == null) {
            openPrice = trade.price
        }
        
        highPrice = maxOf(highPrice ?: trade.price, trade.price)
        lowPrice = minOf(lowPrice ?: trade.price, trade.price)
        closePrice = trade.price
        volume += trade.quantity
        tradeCount++
        volumePrice += trade.price * trade.quantity
    }
    
    /**
     * 重置统计数据
     */
    fun reset() {
        openPrice = null
        highPrice = null
        lowPrice = null
        closePrice = null
        volume = 0
        tradeCount = 0
        volumePrice = 0
    }
    
    /**
     * 检查是否可以构建统计数据
     */
    fun canBuild(): Boolean {
        return openPrice != null && 
               highPrice != null && 
               lowPrice != null && 
               closePrice != null &&
               volume > 0
    }
    
    /**
     * 构建市场统计数据
     */
    fun build(): MarketStatistics {
        require(canBuild()) { "无法构建市场统计数据，数据不完整" }
        
        return MarketStatistics(
            instrumentId = instrumentId,
            timestamp = Instant.now().toEpochMilli(),
            open = openPrice!!,
            high = highPrice!!,
            low = lowPrice!!,
            close = closePrice!!,
            volume = volume,
            trades = tradeCount,
            vwap = if (volume > 0) volumePrice.toDouble() / volume else 0.0,
            period = period
        )
    }
    
    /**
     * 将时间戳对齐到周期开始
     */
    private fun alignToPeriodStart(timestamp: Long): Long {
        val periodMillis = getPeriodMillis()
        return timestamp - (timestamp % periodMillis)
    }
    
    /**
     * 获取周期的毫秒数
     */
    private fun getPeriodMillis(): Long {
        return when (period) {
            StatisticsPeriod.ONE_MINUTE -> 60_000
            StatisticsPeriod.FIVE_MINUTE -> 5 * 60_000
            StatisticsPeriod.FIFTEEN_MINUTE -> 15 * 60_000
            StatisticsPeriod.THIRTY_MINUTE -> 30 * 60_000
            StatisticsPeriod.ONE_HOUR -> 60 * 60_000
            StatisticsPeriod.FOUR_HOUR -> 4 * 60 * 60_000
            StatisticsPeriod.ONE_DAY -> 24 * 60 * 60_000
        }
    }
} 