package com.hftdc.model

import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.time.Instant
import java.util.*

private val logger = KotlinLogging.logger {}

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
        
        // POST_ONLY 订单特殊处理
        if (order.type == OrderType.POST_ONLY) {
            return handlePostOnlyOrder(order)
        }
        
        // FOK 订单特殊处理
        if (order.type == OrderType.FOK || order.timeInForce == TimeInForce.FOK) {
            return handleFokOrder(order)
        }
        
        // IOC 订单特殊处理
        if (order.type == OrderType.IOC || order.timeInForce == TimeInForce.IOC) {
            return handleIocOrder(order)
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
    fun cancelOrder(orderId: Long): Order? {
        // 获取订单
        val order = ordersById[orderId] ?: return null
        
        // 检查订单是否可以取消
        if (!order.canBeCanceled()) {
            return null
        }
        
        // 将订单从价格级别中移除
        val price = order.price ?: return null
        val side = order.side
        val orders = if (side == OrderSide.BUY) buyOrders[price] else sellOrders[price]
        
        orders?.remove(order)
        
        // 如果价格级别为空，则移除该价格级别
        if (orders?.isEmpty() == true) {
            if (side == OrderSide.BUY) {
                buyOrders.remove(price)
            } else {
                sellOrders.remove(price)
            }
        }
        
        // 更新订单状态为已取消
        val canceledOrder = order.withUpdatedStatus(OrderStatus.CANCELED)
        
        // 更新订单缓存
        ordersById[orderId] = canceledOrder
        
        // 更新最后更新时间
        lastUpdated = Instant.now().toEpochMilli()
        
        return canceledOrder
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
                if (sellOrders.isEmpty()) {
                    // 没有卖单，无法成交
                    logger.debug { "市价买单 ${order.id} 没有可匹配的卖单" }
                    emptyList()
                } else {
                    val highestPrice = sellOrders.firstKey() * 2
                    matchBuyOrder(order.copy(price = highestPrice))
                }
            }
            OrderSide.SELL -> {
                // 为市价卖单找到最低的可接受价格
                if (buyOrders.isEmpty()) {
                    // 没有买单，无法成交
                    logger.debug { "市价卖单 ${order.id} 没有可匹配的买单" }
                    emptyList()
                } else {
                    val lowestPrice = buyOrders.firstKey() / 2
                    matchSellOrder(order.copy(price = lowestPrice))
                }
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
    fun getPendingOrdersCount(): Int {
        return ordersById.values.count { 
            it.status == OrderStatus.NEW || it.status == OrderStatus.PARTIALLY_FILLED 
        }
    }
    
    /**
     * 获取最后更新时间
     */
    fun getLastUpdated(): Long = lastUpdated
    
    /**
     * 从快照恢复订单簿状态
     */
    fun restoreFromSnapshot(snapshot: OrderBookSnapshot) {
        // 确保快照与当前订单簿匹配
        if (snapshot.instrumentId != this.instrumentId) {
            throw IllegalArgumentException("快照品种ID ${snapshot.instrumentId} 与订单簿品种ID ${this.instrumentId} 不匹配")
        }
        
        // 清空当前订单簿
        buyOrders.clear()
        sellOrders.clear()
        ordersById.clear()
        
        // 恢复买单价格层级
        snapshot.bids.forEach { level ->
            val placeholderId = generatePlaceholderId("BID", level.price)
            val placeholderOrder = Order(
                id = placeholderId,
                userId = 0, // 系统用户ID
                instrumentId = instrumentId,
                price = level.price,
                quantity = level.quantity,
                remainingQuantity = level.quantity,
                side = OrderSide.BUY,
                status = OrderStatus.NEW,
                type = OrderType.LIMIT,
                timeInForce = TimeInForce.GTC,
                timestamp = snapshot.timestamp,
                lastUpdated = snapshot.timestamp,
                isPlaceholder = true // 标记为占位订单
            )
            
            // 将占位订单添加到订单簿
            val orders = buyOrders.getOrPut(level.price) { mutableListOf() }
            orders.add(placeholderOrder)
            ordersById[placeholderId] = placeholderOrder
        }
        
        // 恢复卖单价格层级
        snapshot.asks.forEach { level ->
            val placeholderId = generatePlaceholderId("ASK", level.price)
            val placeholderOrder = Order(
                id = placeholderId,
                userId = 0, // 系统用户ID
                instrumentId = instrumentId,
                price = level.price,
                quantity = level.quantity,
                remainingQuantity = level.quantity,
                side = OrderSide.SELL,
                status = OrderStatus.NEW,
                type = OrderType.LIMIT,
                timeInForce = TimeInForce.GTC,
                timestamp = snapshot.timestamp,
                lastUpdated = snapshot.timestamp,
                isPlaceholder = true // 标记为占位订单
            )
            
            // 将占位订单添加到订单簿
            val orders = sellOrders.getOrPut(level.price) { mutableListOf() }
            orders.add(placeholderOrder)
            ordersById[placeholderId] = placeholderOrder
        }
        
        // 恢复时间戳
        lastUpdated = snapshot.timestamp
        
        logger.info { "从快照恢复订单簿 $instrumentId, 时间戳: $lastUpdated, 买单层级: ${snapshot.bids.size}, 卖单层级: ${snapshot.asks.size}" }
    }
    
    /**
     * 生成占位订单ID
     */
    private fun generatePlaceholderId(prefix: String, price: Long): Long {
        // 通过前缀、价格和时间戳生成一个唯一标识符
        return "${prefix}_${price}_${System.nanoTime()}".hashCode().toLong()
    }
    
    /**
     * 处理POST_ONLY订单 - 只有在不会立即成交的情况下才加入订单簿
     */
    private fun handlePostOnlyOrder(order: Order): List<Trade> {
        // 检查订单是否会立即成交
        val wouldExecute = when (order.side) {
            OrderSide.BUY -> {
                if (sellOrders.isEmpty()) {
                    false // 没有卖单，不会立即成交
                } else {
                    val lowestAsk = sellOrders.firstKey()
                    order.price != null && order.price >= lowestAsk
                }
            }
            OrderSide.SELL -> {
                if (buyOrders.isEmpty()) {
                    false // 没有买单，不会立即成交
                } else {
                    val highestBid = buyOrders.firstKey()
                    order.price != null && order.price <= highestBid
                }
            }
        }
        
        // 如果会立即成交，则拒绝此订单（返回空交易列表）
        if (wouldExecute) {
            // 更新订单状态为已取消，并添加到缓存
            val rejectedOrder = order.withUpdatedStatus(OrderStatus.REJECTED)
            ordersById[order.id] = rejectedOrder
            logger.debug { "POST_ONLY订单 ${order.id} 会立即成交，已拒绝" }
            return emptyList()
        }
        
        // 否则，将订单添加到订单簿
        addToOrderBook(order)
        logger.debug { "POST_ONLY订单 ${order.id} 已添加到订单簿" }
        return emptyList()
    }
    
    /**
     * 处理FOK（Fill or Kill）订单 - 必须全部成交或全部取消
     */
    private fun handleFokOrder(order: Order): List<Trade> {
        // 检查订单是否可以完全成交
        val availableQuantity = getAvailableQuantityFor(order)
        
        // 如果可用数量小于订单数量，则取消订单
        if (availableQuantity < order.quantity) {
            // 更新订单状态为已取消，并添加到缓存
            val canceledOrder = order.withUpdatedStatus(OrderStatus.CANCELED)
            ordersById[order.id] = canceledOrder
            logger.debug { "FOK订单 ${order.id} 无法完全成交，已取消" }
            return emptyList()
        }
        
        // 否则，执行正常撮合
        val trades = when (order.side) {
            OrderSide.BUY -> matchBuyOrder(order)
            OrderSide.SELL -> matchSellOrder(order)
        }
        
        // 验证是否完全成交
        val isFullyFilled = trades.sumOf { it.quantity } == order.quantity
        
        // 如果没有完全成交（理论上不应该发生），记录错误
        if (!isFullyFilled) {
            logger.error { "FOK订单 ${order.id} 预计可以完全成交，但实际未完全成交" }
        }
        
        return trades
    }
    
    /**
     * 获取订单可成交的数量
     */
    private fun getAvailableQuantityFor(order: Order): Long {
        var availableQty = 0L
        val price = order.price ?: return 0L
        
        when (order.side) {
            OrderSide.BUY -> {
                // 对于买单，检查所有价格小于等于买单价格的卖单
                for ((sellPrice, sellOrders) in sellOrders) {
                    if (sellPrice > price) break
                    
                    // 累加可成交数量
                    for (sellOrder in sellOrders) {
                        // 跳过同一用户的订单
                        if (sellOrder.userId == order.userId) continue
                        availableQty += sellOrder.remainingQuantity
                    }
                }
            }
            OrderSide.SELL -> {
                // 对于卖单，检查所有价格大于等于卖单价格的买单
                for ((buyPrice, buyOrders) in buyOrders) {
                    if (buyPrice < price) break
                    
                    // 累加可成交数量
                    for (buyOrder in buyOrders) {
                        // 跳过同一用户的订单
                        if (buyOrder.userId == order.userId) continue
                        availableQty += buyOrder.remainingQuantity
                    }
                }
            }
        }
        
        return availableQty
    }
    
    /**
     * 处理IOC（Immediate or Cancel）订单 - 立即成交可成交部分，取消剩余部分
     */
    private fun handleIocOrder(order: Order): List<Trade> {
        // 执行正常撮合
        val trades = when (order.side) {
            OrderSide.BUY -> matchBuyOrder(order)
            OrderSide.SELL -> matchSellOrder(order)
        }
        
        // 获取更新后的订单
        val updatedOrder = ordersById[order.id]
        
        // 如果有剩余数量，则取消
        if (updatedOrder != null && updatedOrder.remainingQuantity > 0) {
            val canceledOrder = updatedOrder.withUpdatedStatus(OrderStatus.CANCELED)
            ordersById[order.id] = canceledOrder
            logger.debug { "IOC订单 ${order.id} 部分成交，剩余部分已取消" }
        } else if (trades.isEmpty()) {
            // 如果没有成交，标记为取消
            val canceledOrder = order.withUpdatedStatus(OrderStatus.CANCELED)
            ordersById[order.id] = canceledOrder
            logger.debug { "IOC订单 ${order.id} 未成交，已取消" }
        } else {
            logger.debug { "IOC订单 ${order.id} 已完全成交" }
        }
        
        // 返回成交记录
        return trades
    }
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