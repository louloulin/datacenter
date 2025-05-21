package com.hftdc.journal

import com.hftdc.model.Order
import com.hftdc.model.OrderBookSnapshot
import com.hftdc.model.SerializableMessage
import com.hftdc.model.Trade
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 日志事件接口 - 所有可以被记录的事件都应该实现此接口
 */
interface JournalEvent : SerializableMessage {
    val eventId: Long
    val timestamp: Long
    val type: EventType
}

/**
 * 事件类型
 */
enum class EventType {
    ORDER_SUBMITTED,
    ORDER_ACCEPTED,
    ORDER_REJECTED,
    ORDER_EXECUTED,
    ORDER_CANCELED,
    ORDER_EXPIRED,
    TRADE_CREATED,
    SNAPSHOT_CREATED
}

/**
 * 订单提交事件
 */
@Serializable
@SerialName("order_submitted")
data class OrderSubmittedEvent(
    override val eventId: Long,
    override val timestamp: Long = Instant.now().toEpochMilli(),
    val order: Order
) : JournalEvent {
    override val type: EventType = EventType.ORDER_SUBMITTED
}

/**
 * 订单接受事件
 */
@Serializable
@SerialName("order_accepted")
data class OrderAcceptedEvent(
    override val eventId: Long,
    override val timestamp: Long = Instant.now().toEpochMilli(),
    val orderId: Long,
    val instrumentId: String
) : JournalEvent {
    override val type: EventType = EventType.ORDER_ACCEPTED
}

/**
 * 订单拒绝事件
 */
@Serializable
@SerialName("order_rejected")
data class OrderRejectedEvent(
    override val eventId: Long,
    override val timestamp: Long = Instant.now().toEpochMilli(),
    val orderId: Long,
    val reason: String
) : JournalEvent {
    override val type: EventType = EventType.ORDER_REJECTED
}

/**
 * 订单执行事件
 */
@Serializable
@SerialName("order_executed")
data class OrderExecutedEvent(
    override val eventId: Long,
    override val timestamp: Long = Instant.now().toEpochMilli(),
    val orderId: Long,
    val price: Long,
    val quantity: Long,
    val remainingQuantity: Long
) : JournalEvent {
    override val type: EventType = EventType.ORDER_EXECUTED
}

/**
 * 订单取消事件
 */
@Serializable
@SerialName("order_canceled")
data class OrderCanceledEvent(
    override val eventId: Long,
    override val timestamp: Long = Instant.now().toEpochMilli(),
    val orderId: Long,
    val reason: String? = null
) : JournalEvent {
    override val type: EventType = EventType.ORDER_CANCELED
}

/**
 * 交易创建事件
 */
@Serializable
@SerialName("trade_created")
data class TradeCreatedEvent(
    override val eventId: Long,
    override val timestamp: Long = Instant.now().toEpochMilli(),
    val trade: Trade
) : JournalEvent {
    override val type: EventType = EventType.TRADE_CREATED
}

/**
 * 快照创建事件
 */
@Serializable
@SerialName("snapshot_created")
data class SnapshotCreatedEvent(
    override val eventId: Long,
    override val timestamp: Long = Instant.now().toEpochMilli(),
    val snapshotId: String,
    val instrumentId: String
) : JournalEvent {
    override val type: EventType = EventType.SNAPSHOT_CREATED
}

/**
 * 订单簿快照
 */
@Serializable
data class JournalSnapshot(
    val id: String,
    val timestamp: Long = Instant.now().toEpochMilli(),
    val instrumentId: String,
    val snapshot: OrderBookSnapshot
) 