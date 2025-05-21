package com.hftdc.journal

import com.hftdc.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

class OrderBookRestoreTest {
    
    @Test
    fun `test OrderBook restore from snapshot`() {
        // 准备数据
        val instrumentId = "BTC-USDT"
        val timestamp = Instant.now().toEpochMilli()
        
        // 创建一个新的订单簿
        val orderBook = OrderBook(instrumentId)
        
        // 创建一个订单簿快照
        val snapshot = OrderBookSnapshot(
            instrumentId = instrumentId,
            bids = listOf(
                OrderBookLevel(price = 10000, quantity = 5),
                OrderBookLevel(price = 9900, quantity = 10)
            ),
            asks = listOf(
                OrderBookLevel(price = 10100, quantity = 3),
                OrderBookLevel(price = 10200, quantity = 8)
            ),
            timestamp = timestamp
        )
        
        // 执行恢复
        orderBook.restoreFromSnapshot(snapshot)
        
        // 验证恢复结果
        val recoveredSnapshot = orderBook.getSnapshot(10)
        
        // 验证时间戳被正确恢复
        assertEquals(timestamp, orderBook.getLastUpdated())
        
        // 验证买单价格层级
        assertEquals(2, recoveredSnapshot.bids.size)
        assertEquals(10000, recoveredSnapshot.bids[0].price)
        assertEquals(5, recoveredSnapshot.bids[0].quantity)
        assertEquals(9900, recoveredSnapshot.bids[1].price)
        assertEquals(10, recoveredSnapshot.bids[1].quantity)
        
        // 验证卖单价格层级
        assertEquals(2, recoveredSnapshot.asks.size)
        assertEquals(10100, recoveredSnapshot.asks[0].price)
        assertEquals(3, recoveredSnapshot.asks[0].quantity)
        assertEquals(10200, recoveredSnapshot.asks[1].price)
        assertEquals(8, recoveredSnapshot.asks[1].quantity)
        
        // 验证订单数量
        val allOrders = orderBook.getAllOrders()
        assertEquals(4, allOrders.size) // 2个买单 + 2个卖单
        
        // 验证占位订单的特性
        val placeholderOrders = allOrders.filter { it.isPlaceholder }
        assertEquals(4, placeholderOrders.size) // 所有订单都应该是占位订单
    }
    
    @Test
    fun `test restore with different instrumentId should throw exception`() {
        val orderBook = OrderBook("BTC-USDT")
        val snapshot = OrderBookSnapshot(
            instrumentId = "ETH-USDT", // 与订单簿不同的品种ID
            bids = listOf(OrderBookLevel(price = 100, quantity = 1)),
            asks = listOf(),
            timestamp = Instant.now().toEpochMilli()
        )
        
        // 测试异常
        val exception = assertThrows(IllegalArgumentException::class.java) {
            orderBook.restoreFromSnapshot(snapshot)
        }
        
        // 验证异常消息
        assertTrue(exception.message?.contains("不匹配") ?: false)
    }
    
    @Test
    fun `test restore with empty snapshot`() {
        val instrumentId = "BTC-USDT"
        val orderBook = OrderBook(instrumentId)
        
        // 添加一些初始订单
        val buyOrder = Order(
            id = 1L,
            userId = 1L,
            instrumentId = instrumentId,
            price = 10000L,
            quantity = 5L,
            remainingQuantity = 5L,
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = Instant.now().toEpochMilli()
        )
        
        orderBook.addOrder(buyOrder)
        
        // 确认订单簿中有订单
        assertTrue(orderBook.getAllOrders().isNotEmpty())
        
        // 创建一个空快照
        val emptySnapshot = OrderBookSnapshot(
            instrumentId = instrumentId,
            bids = listOf(),
            asks = listOf(),
            timestamp = Instant.now().toEpochMilli()
        )
        
        // 恢复空快照
        orderBook.restoreFromSnapshot(emptySnapshot)
        
        // 验证订单簿被清空
        assertTrue(orderBook.getAllOrders().isEmpty())
        assertTrue(orderBook.getSnapshot(10).bids.isEmpty())
        assertTrue(orderBook.getSnapshot(10).asks.isEmpty())
    }
} 