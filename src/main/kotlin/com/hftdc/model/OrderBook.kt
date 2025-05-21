package com.hftdc.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.*

/**
 * 订单簿实体 - 管理单个交易品种的所有订单
 */
class OrderBook(val instrumentId: String) {
    // 买单 - 按价格倒序排列（高价在前）
    private val buyOrders = TreeMap<Long, MutableList<Order>>(Comparator.reverseOrder())
    
    // 卖单 - 按价格正序排列（低价在前）
    private val sellOrders = TreeMap<Long, MutableList<Order>>()
    
    // 订单ID到订单的映射，用于快速查找
    private val ordersById = mutableMapOf<Long, Order>()
    
    // 最后更新时间戳
    private var lastUpdated: Long = Instant.now().toEpochMilli()
    
    /**
     * 添加订单到订单簿
     */
    fun addOrder(order: Order): List<Trade> {
        // 市价单特殊处理，立即尝试撮合
        if (order.type == OrderType.MARKET) {
            return matchMarketOrder(order)
        }
        
        // 限价单先尝试撮合
        val trades = when (order.side) {
            OrderSide.BUY -> matchBuyOrder(order)
            OrderSide.SELL -> matchSellOrder(order)
        }
        
        // 如果仍有剩余数量且不是IOC/FOK，则加入订单簿
        val remainingOrder = if (trades.isNotEmpty()) {
            ordersById[order.id]
        } else {
            order
        }
        
        if (remainingOrder != null && 
            remainingOrder.remainingQuantity > 0 && 
            remainingOrder.timeInForce != TimeInForce.IOC && 
            remainingOrder.timeInForce != TimeInForce.FOK) {
            
            addToOrderBook(remainingOrder)
        }
        
        lastUpdated = Instant.now().toEpochMilli()
        return trades
    }
    
    /**
     * 取消订单
     */
    fun cancelOrder(orderId: Long): Boolean {
        val order = ordersById[orderId] ?: return false
        
        if (!order.canBeCanceled()) {
            return false
        }
        
        // 从订单簿中移除
        val priceLevel = order.price ?: return false
        val orderMap = if (order.side == OrderSide.BUY) buyOrders else sellOrders
        
        orderMap[priceLevel]?.let { orders ->
            orders.removeIf { it.id == orderId }
            if (orders.isEmpty()) {
                orderMap.remove(priceLevel)
            }
        }
        
        // 更新订单状态
        val canceledOrder = order.withUpdatedStatus(OrderStatus.CANCELED)
        ordersById[orderId] = canceledOrder
        
        lastUpdated = Instant.now().toEpochMilli()
        return true
    }
    
    /**
     * 撮合买单
     */
    private fun matchBuyOrder(order: Order): List<Trade> {
        if (sellOrders.isEmpty() || order.price == null) {
            return emptyList()
        }
        
        val trades = mutableListOf<Trade>()
        var remainingQty = order.quantity
        var updatedOrder = order
        
        // 尝试匹配卖单
        val sellIterator = sellOrders.entries.iterator()
        while (sellIterator.hasNext() && remainingQty > 0) {
            val (sellPrice, sellOrdersAtPrice) = sellIterator.next()
            
            // 如果买单价格小于卖单价格，无法匹配
            if (order.price < sellPrice) {
                break
            }
            
            val sellOrdersIterator = sellOrdersAtPrice.iterator()
            while (sellOrdersIterator.hasNext() && remainingQty > 0) {
                val sellOrder = sellOrdersIterator.next()
                
                // 跳过同一用户的订单
                if (sellOrder.userId == order.userId) {
                    continue
                }
                
                // 计算成交数量
                val tradeQty = minOf(remainingQty, sellOrder.remainingQuantity)
                
                // 创建交易记录
                val trade = Trade(
                    id = generateTradeId(),
                    buyOrderId = order.id,
                    sellOrderId = sellOrder.id,
                    buyUserId = order.userId,
                    sellUserId = sellOrder.userId,
                    instrumentId = instrumentId,
                    price = sellPrice,
                    quantity = tradeQty,
                    makerOrderId = sellOrder.id,
                    takerOrderId = order.id,
                    buyFee = calculateFee(tradeQty, sellPrice, true),
                    sellFee = calculateFee(tradeQty, sellPrice, false)
                )
                trades.add(trade)
                
                // 更新剩余数量
                remainingQty -= tradeQty
                
                // 更新订单状态
                updatedOrder = updatedOrder.withUpdatedStatus(
                    if (remainingQty == 0L) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED,
                    tradeQty,
                    sellPrice
                )
                
                val updatedSellOrder = sellOrder.withUpdatedStatus(
                    if (sellOrder.remainingQuantity - tradeQty == 0L) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED,
                    tradeQty,
                    sellPrice
                )
                
                // 更新映射
                ordersById[order.id] = updatedOrder
                ordersById[sellOrder.id] = updatedSellOrder
                
                // 移除完全成交的卖单
                if (updatedSellOrder.isFullyFilled()) {
                    sellOrdersIterator.remove()
                }
            }
            
            // 如果此价格级别没有卖单了，移除此价格级别
            if (sellOrdersAtPrice.isEmpty()) {
                sellIterator.remove()
            }
        }
        
        return trades
    }
    
    /**
     * 撮合卖单
     */
    private fun matchSellOrder(order: Order): List<Trade> {
        if (buyOrders.isEmpty() || order.price == null) {
            return emptyList()
        }
        
        val trades = mutableListOf<Trade>()
        var remainingQty = order.quantity
        var updatedOrder = order
        
        // 尝试匹配买单
        val buyIterator = buyOrders.entries.iterator()
        while (buyIterator.hasNext() && remainingQty > 0) {
            val (buyPrice, buyOrdersAtPrice) = buyIterator.next()
            
            // 如果卖单价格大于买单价格，无法匹配
            if (order.price > buyPrice) {
                break
            }
            
            val buyOrdersIterator = buyOrdersAtPrice.iterator()
            while (buyOrdersIterator.hasNext() && remainingQty > 0) {
                val buyOrder = buyOrdersIterator.next()
                
                // 跳过同一用户的订单
                if (buyOrder.userId == order.userId) {
                    continue
                }
                
                // 计算成交数量
                val tradeQty = minOf(remainingQty, buyOrder.remainingQuantity)
                
                // 创建交易记录
                val trade = Trade(
                    id = generateTradeId(),
                    buyOrderId = buyOrder.id,
                    sellOrderId = order.id,
                    buyUserId = buyOrder.userId,
                    sellUserId = order.userId,
                    instrumentId = instrumentId,
                    price = buyPrice,
                    quantity = tradeQty,
                    makerOrderId = buyOrder.id,
                    takerOrderId = order.id,
                    buyFee = calculateFee(tradeQty, buyPrice, true),
                    sellFee = calculateFee(tradeQty, buyPrice, false)
                )
                trades.add(trade)
                
                // 更新剩余数量
                remainingQty -= tradeQty
                
                // 更新订单状态
                updatedOrder = updatedOrder.withUpdatedStatus(
                    if (remainingQty == 0L) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED,
                    tradeQty,
                    buyPrice
                )
                
                val updatedBuyOrder = buyOrder.withUpdatedStatus(
                    if (buyOrder.remainingQuantity - tradeQty == 0L) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED,
                    tradeQty,
                    buyPrice
                )
                
                // 更新映射
                ordersById[order.id] = updatedOrder
                ordersById[buyOrder.id] = updatedBuyOrder
                
                // 移除完全成交的买单
                if (updatedBuyOrder.isFullyFilled()) {
                    buyOrdersIterator.remove()
                }
            }
            
            // 如果此价格级别没有买单了，移除此价格级别
            if (buyOrdersAtPrice.isEmpty()) {
                buyIterator.remove()
            }
        }
        
        return trades
    }
    
    /**
     * 撮合市价单
     */
    private fun matchMarketOrder(order: Order): List<Trade> {
        return when (order.side) {
            OrderSide.BUY -> {
                // 为市价买单找到最高的可接受价格
                val highestPrice = sellOrders.firstKey()?.let { it * 2 } ?: return emptyList()
                matchBuyOrder(order.copy(price = highestPrice))
            }
            OrderSide.SELL -> {
                // 为市价卖单找到最低的可接受价格
                val lowestPrice = buyOrders.firstKey()?.let { it / 2 } ?: return emptyList()
                matchSellOrder(order.copy(price = lowestPrice))
            }
        }
    }
    
    /**
     * 添加订单到订单簿
     */
    private fun addToOrderBook(order: Order) {
        val price = order.price ?: return
        val orders = if (order.side == OrderSide.BUY) {
            buyOrders.getOrPut(price) { mutableListOf() }
        } else {
            sellOrders.getOrPut(price) { mutableListOf() }
        }
        
        orders.add(order)
        ordersById[order.id] = order
    }
    
    /**
     * 计算订单簿快照
     */
    fun getSnapshot(depth: Int): OrderBookSnapshot {
        val bids = buyOrders.entries.take(depth).flatMap { (price, orders) ->
            val totalQty = orders.sumOf { it.remainingQuantity }
            listOf(OrderBookLevel(price, totalQty))
        }
        
        val asks = sellOrders.entries.take(depth).flatMap { (price, orders) ->
            val totalQty = orders.sumOf { it.remainingQuantity }
            listOf(OrderBookLevel(price, totalQty))
        }
        
        return OrderBookSnapshot(
            instrumentId = instrumentId,
            bids = bids,
            asks = asks,
            timestamp = Instant.now().toEpochMilli()
        )
    }
    
    /**
     * 生成交易ID
     */
    private fun generateTradeId(): Long {
        // 简单实现，生产环境需要更复杂的唯一ID生成方式
        return Instant.now().toEpochMilli()
    }
    
    /**
     * 计算交易费用
     */
    private fun calculateFee(quantity: Long, price: Long, isBuyer: Boolean): Long {
        // 简单实现，固定费率
        val amount = quantity * price
        val feeRate = if (isBuyer) 0.001 else 0.001 // 0.1%
        return (amount * feeRate).toLong()
    }
    
    /**
     * 获取订单
     */
    fun getOrder(orderId: Long): Order? = ordersById[orderId]
    
    /**
     * 获取所有订单
     */
    fun getAllOrders(): List<Order> = ordersById.values.toList()
    
    /**
     * 获取未成交订单数量
     */
    fun getPendingOrdersCount(): Int = buyOrders.values.sumOf { it.size } + sellOrders.values.sumOf { it.size }
    
    /**
     * 获取最后更新时间
     */
    fun getLastUpdated(): Long = lastUpdated
}

/**
 * 订单簿级别 - 表示订单簿中某个价格级别的汇总信息
 */
@Serializable
data class OrderBookLevel(
    val price: Long,      // 价格
    val quantity: Long    // 该价格级别的总数量
) : SerializableMessage

/**
 * 订单簿快照 - 表示某个时刻订单簿的状态
 */
@Serializable
data class OrderBookSnapshot(
    val instrumentId: String,        // 交易品种ID
    val bids: List<OrderBookLevel>,  // 买单列表
    val asks: List<OrderBookLevel>,  // 卖单列表
    val timestamp: Long              // 快照时间戳
) : SerializableMessage {
    
    /**
     * 获取买一价
     */
    fun getBestBidPrice(): Long? = bids.firstOrNull()?.price
    
    /**
     * 获取卖一价
     */
    fun getBestAskPrice(): Long? = asks.firstOrNull()?.price
    
    /**
     * 获取价差
     */
    fun getSpread(): Long? {
        val bestBid = getBestBidPrice() ?: return null
        val bestAsk = getBestAskPrice() ?: return null
        return bestAsk - bestBid
    }
} 