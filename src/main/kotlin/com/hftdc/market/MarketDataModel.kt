package com.hftdc.market

import com.hftdc.model.OrderBookLevel
import com.hftdc.model.SerializableMessage
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 市场数据更新
 */
@Serializable
data class MarketDataUpdate(
    val instrumentId: String,
    val timestamp: Long = Instant.now().toEpochMilli(),
    val bids: List<OrderBookLevel>,
    val asks: List<OrderBookLevel>,
    val lastTradePrice: Long? = null,
    val lastTradeQuantity: Long? = null,
    val lastTradeTimestamp: Long? = null,
    val openPrice: Long? = null,
    val highPrice: Long? = null,
    val lowPrice: Long? = null,
    val closePrice: Long? = null,
    val volume: Long? = null,
    val updateType: MarketDataUpdateType
) : SerializableMessage

/**
 * 市场数据更新类型
 */
enum class MarketDataUpdateType {
    SNAPSHOT,       // 完整快照
    INCREMENTAL,    // 增量更新
    TRADE,          // 成交信息
    STATISTICS      // 统计信息
}

/**
 * 市场数据订阅请求
 */
@Serializable
data class MarketDataSubscription(
    val instrumentId: String,
    val depth: Int = 10,
    val frequency: MarketDataFrequency = MarketDataFrequency.REALTIME,
    val includeStatistics: Boolean = false,
    val includeTrades: Boolean = true
) : SerializableMessage

/**
 * 市场数据频率
 */
enum class MarketDataFrequency {
    REALTIME,       // 实时更新
    ONE_SECOND,     // 1秒更新
    FIVE_SECOND,    // 5秒更新
    ONE_MINUTE      // 1分钟更新
}

/**
 * 交易统计数据
 */
@Serializable
data class MarketStatistics(
    val instrumentId: String,
    val timestamp: Long = Instant.now().toEpochMilli(),
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long,
    val trades: Int,
    val vwap: Double, // 成交量加权平均价格
    val period: StatisticsPeriod
) : SerializableMessage

/**
 * 统计数据周期
 */
enum class StatisticsPeriod {
    ONE_MINUTE,
    FIVE_MINUTE,
    FIFTEEN_MINUTE,
    THIRTY_MINUTE,
    ONE_HOUR,
    FOUR_HOUR,
    ONE_DAY
} 