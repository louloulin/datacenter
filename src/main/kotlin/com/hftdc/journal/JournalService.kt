package com.hftdc.journal

import com.hftdc.model.Order
import com.hftdc.model.OrderBookSnapshot
import com.hftdc.model.Trade
import java.time.Instant

/**
 * 日志事件接口 - 所有需要记录的事件都应实现此接口
 */
interface JournalEvent {
    val eventId: Long
    val timestamp: Long
    val type: EventType
}

/**
 * 事件类型枚举
 */
enum class EventType {
    ORDER_SUBMITTED,
    ORDER_CANCELED,
    TRADE_CREATED,
    SNAPSHOT_CREATED
}

/**
 * 订单提交事件
 */
data class OrderSubmittedEvent(
    override val eventId: Long,
    override val timestamp: Long,
    val order: Order
) : JournalEvent {
    override val type: EventType = EventType.ORDER_SUBMITTED
}

/**
 * 订单取消事件
 */
data class OrderCanceledEvent(
    override val eventId: Long,
    override val timestamp: Long,
    val orderId: Long,
    val instrumentId: String? = null,
    val reason: String = "USER_CANCELED"
) : JournalEvent {
    override val type: EventType = EventType.ORDER_CANCELED
}

/**
 * 交易创建事件
 */
data class TradeCreatedEvent(
    override val eventId: Long,
    override val timestamp: Long,
    val trade: Trade
) : JournalEvent {
    override val type: EventType = EventType.TRADE_CREATED
}

/**
 * 快照创建事件
 */
data class SnapshotCreatedEvent(
    override val eventId: Long,
    override val timestamp: Long,
    val instrumentId: String,
    val snapshotId: String
) : JournalEvent {
    override val type: EventType = EventType.SNAPSHOT_CREATED
}

/**
 * 快照数据类 - 代表某个时间点的一个交易品种的订单簿状态
 */
data class InstrumentSnapshot(
    val id: String,
    val instrumentId: String,
    val timestamp: Long,
    val snapshot: OrderBookSnapshot
)

/**
 * 日志服务接口 - 用于记录和查询交易事件和快照
 */
interface JournalService {
    /**
     * 记录事件
     */
    fun journal(event: JournalEvent)
    
    /**
     * 保存快照并返回快照ID
     */
    fun saveSnapshot(instrumentId: String, snapshot: OrderBookSnapshot): String
    
    /**
     * 获取指定品种的最新快照
     */
    fun getLatestSnapshot(instrumentId: String): InstrumentSnapshot?
    
    /**
     * 获取指定时间戳之后的所有事件
     */
    fun getEventsSince(instrumentId: String, timestamp: Long): List<JournalEvent>
    
    /**
     * 获取指定时间范围内的所有事件
     */
    fun getEventsBetween(instrumentId: String, fromTimestamp: Long, toTimestamp: Long): List<JournalEvent>
    
    /**
     * 获取所有有快照的交易品种ID
     */
    fun getInstrumentsWithSnapshots(): Set<String>
    
    /**
     * 关闭日志服务
     */
    fun shutdown() {
        // 默认实现为空，由具体实现类覆盖
    }
}