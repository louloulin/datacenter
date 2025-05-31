package com.hftdc.disruptorx.example.trading.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * 订单方向
 */
enum class OrderSide {
    BUY, SELL
}

/**
 * 订单类型
 */
enum class OrderType {
    MARKET, LIMIT, STOP, STOP_LIMIT
}

/**
 * 订单状态
 */
enum class OrderStatus {
    CREATED,
    VALIDATED,
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED
}

/**
 * 交易订单
 * 表示交易系统中的一个订单请求
 */
data class TradeOrder(
    val orderId: String = UUID.randomUUID().toString(),
    val clientId: String,
    val symbol: String,
    val side: OrderSide,
    val type: OrderType,
    val price: BigDecimal?,
    val stopPrice: BigDecimal?,
    val quantity: Long,
    var filledQuantity: Long = 0,
    var status: OrderStatus = OrderStatus.CREATED,
    var message: String? = null,
    val timestamp: Long = Instant.now().toEpochMilli(),
    var lastUpdateTime: Long = timestamp
) {
    // 计算订单完成百分比
    fun completionPercentage(): Double {
        return if (quantity > 0) {
            (filledQuantity.toDouble() / quantity.toDouble()) * 100.0
        } else {
            0.0
        }
    }
    
    // 检查订单是否已完成
    fun isComplete(): Boolean {
        return status == OrderStatus.FILLED ||
               status == OrderStatus.CANCELLED ||
               status == OrderStatus.REJECTED
    }
    
    // 检查订单是否有效
    fun isValid(): Boolean {
        // 市价单不需要价格
        if (type == OrderType.MARKET && price != null) {
            return false
        }
        
        // 限价单需要价格
        if (type == OrderType.LIMIT && price == null) {
            return false
        }
        
        // 止损单需要止损价格
        if (type == OrderType.STOP && stopPrice == null) {
            return false
        }
        
        // 止损限价单需要价格和止损价格
        if (type == OrderType.STOP_LIMIT && (price == null || stopPrice == null)) {
            return false
        }
        
        // 数量必须为正数
        if (quantity <= 0) {
            return false
        }
        
        return true
    }
    
    // 更新订单状态
    fun updateStatus(newStatus: OrderStatus, message: String? = null) {
        this.status = newStatus
        this.message = message
        this.lastUpdateTime = Instant.now().toEpochMilli()
    }
    
    // 更新成交数量
    fun updateFilledQuantity(additionalFilledQuantity: Long) {
        require(additionalFilledQuantity > 0) { "填充数量必须为正数" }
        require(filledQuantity + additionalFilledQuantity <= quantity) { "填充数量不能超过订单数量" }
        
        filledQuantity += additionalFilledQuantity
        lastUpdateTime = Instant.now().toEpochMilli()
        
        // 更新状态
        status = if (filledQuantity == quantity) {
            OrderStatus.FILLED
        } else if (filledQuantity > 0) {
            OrderStatus.PARTIALLY_FILLED
        } else {
            status
        }
    }
    
    companion object {
        /**
         * 创建市价买单
         */
        fun marketBuy(clientId: String, symbol: String, quantity: Long): TradeOrder {
            return TradeOrder(
                clientId = clientId,
                symbol = symbol,
                side = OrderSide.BUY,
                type = OrderType.MARKET,
                price = null,
                stopPrice = null,
                quantity = quantity
            )
        }
        
        /**
         * 创建市价卖单
         */
        fun marketSell(clientId: String, symbol: String, quantity: Long): TradeOrder {
            return TradeOrder(
                clientId = clientId,
                symbol = symbol,
                side = OrderSide.SELL,
                type = OrderType.MARKET,
                price = null,
                stopPrice = null,
                quantity = quantity
            )
        }
        
        /**
         * 创建限价买单
         */
        fun limitBuy(clientId: String, symbol: String, price: BigDecimal, quantity: Long): TradeOrder {
            return TradeOrder(
                clientId = clientId,
                symbol = symbol,
                side = OrderSide.BUY,
                type = OrderType.LIMIT,
                price = price,
                stopPrice = null,
                quantity = quantity
            )
        }
        
        /**
         * 创建限价卖单
         */
        fun limitSell(clientId: String, symbol: String, price: BigDecimal, quantity: Long): TradeOrder {
            return TradeOrder(
                clientId = clientId,
                symbol = symbol,
                side = OrderSide.SELL,
                type = OrderType.LIMIT,
                price = price,
                stopPrice = null,
                quantity = quantity
            )
        }
    }
}

/**
 * 交易记录
 */
data class Trade(
    val tradeId: String,
    val orderId: String,
    val symbol: String,
    val side: OrderSide,
    val quantity: Long,
    val price: BigDecimal,
    val timestamp: Long
)

/**
 * 市场数据
 */
data class MarketData(
    val symbol: String,
    val bidPrice: BigDecimal,
    val askPrice: BigDecimal,
    val lastPrice: BigDecimal,
    val volume: Long,
    val timestamp: Long
)