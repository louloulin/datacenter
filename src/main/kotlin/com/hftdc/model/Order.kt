package com.hftdc.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 订单侧
 */
enum class OrderSide {
    BUY, SELL
}

/**
 * 订单类型
 */
enum class OrderType {
    LIMIT,      // 限价单
    MARKET,     // 市价单
    IOC,        // 立即成交或取消
    FOK,        // 全部成交或取消
    POST_ONLY   // 只做挂单，不成交
}

/**
 * 订单状态
 */
enum class OrderStatus {
    NEW,        // 新订单
    PARTIALLY_FILLED, // 部分成交
    FILLED,     // 全部成交
    CANCELED,   // 已取消
    REJECTED    // 被拒绝
}

/**
 * 有效期类型
 */
enum class TimeInForce {
    GTC,        // 成交为止
    DAY,        // 当日有效
    GTD,        // 指定日期前有效
    IOC,        // 立即成交或取消
    FOK         // 全部成交或取消
}

/**
 * 订单实体
 */
@Serializable
data class Order(
    val id: Long,                     // 订单ID
    val userId: Long,                 // 用户ID
    val instrumentId: String,         // 交易品种ID
    val price: Long?,                 // 价格（市价单为null）
    val quantity: Long,               // 数量
    val side: OrderSide,              // 订单方向
    val type: OrderType,              // 订单类型
    val timeInForce: TimeInForce,     // 有效期类型
    val status: OrderStatus,          // 订单状态
    val filledQuantity: Long = 0,     // 已成交数量
    val remainingQuantity: Long = quantity, // 剩余数量
    val avgExecutionPrice: Long? = null, // 平均成交价格
    val timestamp: Long,              // 创建时间戳
    val lastUpdated: Long = timestamp, // 最后更新时间
    val isPlaceholder: Boolean = false // 是否是占位订单（用于恢复）
) : SerializableMessage {
    
    /**
     * 检查订单是否可以成交
     */
    fun canBeExecuted(): Boolean {
        return status == OrderStatus.NEW || status == OrderStatus.PARTIALLY_FILLED
    }
    
    /**
     * 更新订单状态
     */
    fun withUpdatedStatus(
        newStatus: OrderStatus,
        additionalFilledQty: Long = 0,
        executionPrice: Long? = null
    ): Order {
        val newFilledQty = filledQuantity + additionalFilledQty
        val newRemainingQty = quantity - newFilledQty
        
        // 计算新的平均成交价格
        val newAvgPrice = if (executionPrice != null && additionalFilledQty > 0) {
            if (avgExecutionPrice == null) {
                executionPrice
            } else {
                // 加权平均价格
                ((avgExecutionPrice * filledQuantity) + (executionPrice * additionalFilledQty)) / newFilledQty
            }
        } else {
            avgExecutionPrice
        }
        
        return copy(
            status = newStatus,
            filledQuantity = newFilledQty,
            remainingQuantity = newRemainingQty,
            avgExecutionPrice = newAvgPrice,
            lastUpdated = Instant.now().toEpochMilli()
        )
    }
    
    /**
     * 检查是否完全成交
     */
    fun isFullyFilled(): Boolean = filledQuantity >= quantity
    
    /**
     * 检查是否可以取消
     */
    fun canBeCanceled(): Boolean {
        return status == OrderStatus.NEW || status == OrderStatus.PARTIALLY_FILLED
    }
}

/**
 * 可序列化消息接口
 */
interface SerializableMessage 