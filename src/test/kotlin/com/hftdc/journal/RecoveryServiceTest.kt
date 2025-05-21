package com.hftdc.journal

import com.hftdc.engine.OrderBookManager
import com.hftdc.model.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

class RecoveryServiceTest {
    
    private lateinit var journalService: InMemoryJournalService
    private lateinit var orderBookManager: OrderBookManager
    private lateinit var recoveryService: RecoveryService
    
    @BeforeEach
    fun setUp() {
        journalService = InMemoryJournalService()
        orderBookManager = OrderBookManager(maxInstruments = 10, snapshotIntervalMs = 1000)
        recoveryService = RecoveryService(journalService, orderBookManager)
    }
    
    @Test
    fun `test recovery from snapshot`() {
        // 准备 - 创建一个有买卖单的订单簿快照
        val instrumentId = "BTC-USDT"
        val bids = listOf(
            OrderBookLevel(price = 10000, quantity = 5),
            OrderBookLevel(price = 9900, quantity = 10)
        )
        val asks = listOf(
            OrderBookLevel(price = 10100, quantity = 3),
            OrderBookLevel(price = 10200, quantity = 8)
        )
        val snapshot = OrderBookSnapshot(
            instrumentId = instrumentId,
            bids = bids,
            asks = asks,
            timestamp = Instant.now().toEpochMilli()
        )
        
        // 保存快照到日志服务
        journalService.saveSnapshot(instrumentId, snapshot)
        
        // 执行 - 触发恢复过程
        recoveryService.recover()
        
        // 验证 - 检查订单簿是否正确恢复
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        val recoveredSnapshot = orderBook.getSnapshot(10)
        
        // 验证买单
        assertEquals(2, recoveredSnapshot.bids.size)
        assertEquals(10000, recoveredSnapshot.bids[0].price)
        assertEquals(5, recoveredSnapshot.bids[0].quantity)
        assertEquals(9900, recoveredSnapshot.bids[1].price)
        assertEquals(10, recoveredSnapshot.bids[1].quantity)
        
        // 验证卖单
        assertEquals(2, recoveredSnapshot.asks.size)
        assertEquals(10100, recoveredSnapshot.asks[0].price)
        assertEquals(3, recoveredSnapshot.asks[0].quantity)
        assertEquals(10200, recoveredSnapshot.asks[1].price)
        assertEquals(8, recoveredSnapshot.asks[1].quantity)
    }
    
    @Test
    fun `test recovery from snapshot plus events`() {
        val instrumentId = "ETH-USDT"
        val timestamp = Instant.now().toEpochMilli()
        
        // 1. 创建并保存快照
        val snapshot = OrderBookSnapshot(
            instrumentId = instrumentId,
            bids = listOf(OrderBookLevel(price = 1000, quantity = 5)),
            asks = listOf(OrderBookLevel(price = 1100, quantity = 3)),
            timestamp = timestamp - 1000 // 快照是1秒前的
        )
        journalService.saveSnapshot(instrumentId, snapshot)
        
        // 2. 创建快照之后的新订单事件
        val newOrder = Order(
            id = 1L,
            userId = 123L,
            instrumentId = instrumentId,
            price = 1050L,
            quantity = 2L,
            remainingQuantity = 2L,
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = timestamp - 500
        )
        
        val orderEvent = OrderSubmittedEvent(
            eventId = 1L,
            timestamp = timestamp - 500,
            order = newOrder
        )
        
        journalService.journal(orderEvent)
        
        // 3. 执行恢复
        recoveryService.recover()
        
        // 4. 验证 - 检查订单簿中是否包含快照数据和后续事件
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        val recoveredSnapshot = orderBook.getSnapshot(10)
        
        // 验证快照中的数据
        assertTrue(recoveredSnapshot.bids.any { it.price == 1000L })
        assertTrue(recoveredSnapshot.asks.any { it.price == 1100L })
        
        // 验证后续添加的订单
        assertTrue(recoveredSnapshot.bids.any { it.price == 1050L })
        
        // 验证订单数量
        val allOrders = orderBook.getAllOrders()
        assertTrue(allOrders.any { it.price == 1050L && it.id == 1L })
    }
    
    @Test
    fun `test recovery with order cancellation events`() {
        val instrumentId = "LTC-USDT"
        val timestamp = Instant.now().toEpochMilli()
        
        // 1. 创建并保存基本快照
        val snapshot = OrderBookSnapshot(
            instrumentId = instrumentId,
            bids = listOf(
                OrderBookLevel(price = 500, quantity = 10),
                OrderBookLevel(price = 490, quantity = 15)
            ),
            asks = listOf(
                OrderBookLevel(price = 510, quantity = 8),
                OrderBookLevel(price = 520, quantity = 12)
            ),
            timestamp = timestamp - 2000 // 快照是2秒前的
        )
        journalService.saveSnapshot(instrumentId, snapshot)
        
        // 2. 创建一个订单和取消事件
        val order = Order(
            id = 100L,
            userId = 456L,
            instrumentId = instrumentId,
            price = 505L,
            quantity = 5L,
            remainingQuantity = 5L,
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = timestamp - 1500
        )
        
        val orderEvent = OrderSubmittedEvent(
            eventId = 100L,
            timestamp = timestamp - 1500,
            order = order
        )
        
        val cancelEvent = OrderCanceledEvent(
            eventId = 101L,
            timestamp = timestamp - 1000,
            orderId = 100L,
            reason = "测试取消"
        )
        
        journalService.journal(orderEvent)
        journalService.journal(cancelEvent)
        
        // 3. 执行恢复
        recoveryService.recover()
        
        // 4. 验证 - 被取消的订单不应该出现在当前活跃订单中
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        val allOrders = orderBook.getAllOrders()
        
        // 订单应该在订单簿中但状态为已取消
        val canceledOrder = allOrders.find { it.id == 100L }
        assertNotNull(canceledOrder)
        assertEquals(OrderStatus.CANCELED, canceledOrder?.status)
        
        // 在当前的活跃订单中不应该有这个已取消的订单
        val activeOrders = allOrders.filter { 
            it.status == OrderStatus.NEW || it.status == OrderStatus.PARTIALLY_FILLED 
        }
        assertFalse(activeOrders.any { it.id == 100L })
    }
} 