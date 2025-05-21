package com.hftdc.journal

import com.hftdc.engine.OrderBookManager
import com.hftdc.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecoveryServiceTest {

    private lateinit var journalService: InMemoryJournalService
    private lateinit var orderBookManager: OrderBookManager
    private lateinit var recoveryService: RecoveryService
    
    @BeforeEach
    fun setUp() {
        journalService = InMemoryJournalService()
        orderBookManager = OrderBookManager(maxInstruments = 10, snapshotIntervalMs = 1000, enableInternalSnapshots = false)
        recoveryService = RecoveryService(
            journalService = journalService,
            orderBookManager = orderBookManager,
            config = RecoveryConfig(
                includeEventsBeforeSnapshot = true,
                eventsBeforeSnapshotTimeWindowMs = 10000
            )
        )
    }
    
    @AfterEach
    fun tearDown() {
        orderBookManager.shutdown()
        journalService.shutdown()
    }
    
    @Test
    fun `test basic recovery from snapshot`() {
        // 准备 - 创建订单簿和订单
        val btcOrderBook = orderBookManager.getOrderBook("BTC-USDT")
        
        // 添加初始订单
        val buyOrder = createOrder(1L, "BTC-USDT", 50000L, 2L, OrderSide.BUY)
        val sellOrder = createOrder(2L, "BTC-USDT", 50100L, 3L, OrderSide.SELL)
        
        btcOrderBook.addOrder(buyOrder)
        btcOrderBook.addOrder(sellOrder)
        
        // 创建快照
        val snapshot = btcOrderBook.getSnapshot(10)
        journalService.saveSnapshot("BTC-USDT", snapshot)
        
        // 清空订单簿，模拟系统重启
        orderBookManager.shutdown()
        orderBookManager = OrderBookManager(maxInstruments = 10, snapshotIntervalMs = 1000, enableInternalSnapshots = false)
        
        // 创建新的恢复服务
        recoveryService = RecoveryService(
            journalService = journalService,
            orderBookManager = orderBookManager
        )
        
        // 执行 - 恢复系统
        val recoveryStats = recoveryService.recover()
        
        // 验证 - 检查恢复结果
        assertEquals(1, recoveryStats.instrumentsRecovered, "应该恢复1个品种")
        assertTrue(recoveryStats.ordersRecovered >= 2, "应该恢复至少2个订单")
        
        // 验证订单簿状态
        val recoveredOrderBook = orderBookManager.getOrderBook("BTC-USDT")
        
        // 由于我们不能直接使用getBestBid和getBestAsk方法来验证，
        // 我们可以通过snapshot来验证订单簿的状态
        val recoveredSnapshot = recoveredOrderBook.getSnapshot(10)
        assertFalse(recoveredSnapshot.bids.isEmpty(), "应该有买单")
        assertFalse(recoveredSnapshot.asks.isEmpty(), "应该有卖单")
        assertEquals(50000L, recoveredSnapshot.bids[0].price, "最佳买单价格应该是50000")
        assertEquals(50100L, recoveredSnapshot.asks[0].price, "最佳卖单价格应该是50100")
    }
    
    @Test
    fun `test recovery with events after snapshot`() {
        // 准备 - 创建订单簿和订单，生成快照，然后添加新的订单
        val ethOrderBook = orderBookManager.getOrderBook("ETH-USDT")
        
        // 添加初始订单
        val initialBuyOrder = createOrder(1L, "ETH-USDT", 3000L, 2L, OrderSide.BUY)
        val initialSellOrder = createOrder(2L, "ETH-USDT", 3100L, 3L, OrderSide.SELL)
        
        ethOrderBook.addOrder(initialBuyOrder)
        ethOrderBook.addOrder(initialSellOrder)
        
        // 创建快照
        val snapshot = ethOrderBook.getSnapshot(10)
        journalService.saveSnapshot("ETH-USDT", snapshot)
        
        // 在快照之后添加新订单，并记录到日志
        val newBuyOrder = createOrder(3L, "ETH-USDT", 3050L, 1L, OrderSide.BUY)
        val newSellOrder = createOrder(4L, "ETH-USDT", 3080L, 1L, OrderSide.SELL)
        
        // 记录新订单到日志
        val submitBuyEvent = OrderSubmittedEvent(
            eventId = 1L,
            timestamp = Instant.now().toEpochMilli(),
            order = newBuyOrder
        )
        
        val submitSellEvent = OrderSubmittedEvent(
            eventId = 2L,
            timestamp = Instant.now().toEpochMilli(),
            order = newSellOrder
        )
        
        journalService.journal(submitBuyEvent)
        journalService.journal(submitSellEvent)
        
        // 清空订单簿，模拟系统重启
        orderBookManager.shutdown()
        orderBookManager = OrderBookManager(maxInstruments = 10, snapshotIntervalMs = 1000, enableInternalSnapshots = false)
        
        // 创建新的恢复服务
        recoveryService = RecoveryService(
            journalService = journalService,
            orderBookManager = orderBookManager
        )
        
        // 执行 - 恢复系统
        val recoveryStats = recoveryService.recover()
        
        // 验证 - 检查恢复结果
        assertEquals(1, recoveryStats.instrumentsRecovered, "应该恢复1个品种")
        assertTrue(recoveryStats.ordersRecovered >= 2, "应该恢复至少2个订单")
        assertTrue(recoveryStats.eventsApplied >= 2, "应该应用至少2个事件")
        
        // 验证订单簿状态，确认快照后的事件已被应用
        val recoveredOrderBook = orderBookManager.getOrderBook("ETH-USDT")
        
        // 由于我们不能直接使用getBestBid和getBestAsk方法，
        // 我们可以通过snapshot来验证订单簿的状态
        val recoveredSnapshot = recoveredOrderBook.getSnapshot(10)
        assertFalse(recoveredSnapshot.bids.isEmpty(), "应该有买单")
        assertFalse(recoveredSnapshot.asks.isEmpty(), "应该有卖单")
        
        // 验证最佳买单和卖单
        val bestBidPrice = recoveredSnapshot.bids[0].price
        val bestAskPrice = recoveredSnapshot.asks[0].price
        
        assertEquals(3050L, bestBidPrice, "最佳买单价格应该是3050（快照后添加的订单）")
        assertEquals(3080L, bestAskPrice, "最佳卖单价格应该是3080（快照后添加的订单）")
    }
    
    @Test
    fun `test recovery with canceled orders`() {
        // 准备 - 创建订单簿和订单，生成快照，然后取消某些订单
        val ltcOrderBook = orderBookManager.getOrderBook("LTC-USDT")
        
        // 添加初始订单
        val order1 = createOrder(1L, "LTC-USDT", 100L, 10L, OrderSide.BUY)
        val order2 = createOrder(2L, "LTC-USDT", 101L, 5L, OrderSide.BUY)
        val order3 = createOrder(3L, "LTC-USDT", 102L, 8L, OrderSide.SELL)
        
        ltcOrderBook.addOrder(order1)
        ltcOrderBook.addOrder(order2)
        ltcOrderBook.addOrder(order3)
        
        // 创建快照
        val snapshot = ltcOrderBook.getSnapshot(10)
        journalService.saveSnapshot("LTC-USDT", snapshot)
        
        // 记录取消订单事件
        val cancelEvent = OrderCanceledEvent(
            eventId = 1L,
            timestamp = Instant.now().toEpochMilli(),
            orderId = 2L,
            instrumentId = "LTC-USDT"
        )
        
        journalService.journal(cancelEvent)
        
        // 清空订单簿，模拟系统重启
        orderBookManager.shutdown()
        orderBookManager = OrderBookManager(maxInstruments = 10, snapshotIntervalMs = 1000, enableInternalSnapshots = false)
        
        // 创建新的恢复服务
        recoveryService = RecoveryService(
            journalService = journalService,
            orderBookManager = orderBookManager
        )
        
        // 执行 - 恢复系统
        val recoveryStats = recoveryService.recover()
        
        // 验证 - 检查恢复结果
        assertTrue(recoveryStats.eventsApplied >= 1, "应该应用至少1个事件（取消订单）")
    }
    
    @Test
    fun `test recovery with trades`() {
        // 准备 - 创建订单簿和订单，生成快照，然后记录交易
        val xrpOrderBook = orderBookManager.getOrderBook("XRP-USDT")
        
        // 添加初始订单
        val buyOrder = createOrder(1L, "XRP-USDT", 50L, 1000L, OrderSide.BUY)  // 使用整数价格
        val sellOrder = createOrder(2L, "XRP-USDT", 50L, 500L, OrderSide.SELL) // 使用整数价格
        
        xrpOrderBook.addOrder(buyOrder)
        
        // 创建快照
        val snapshot = xrpOrderBook.getSnapshot(10)
        journalService.saveSnapshot("XRP-USDT", snapshot)
        
        // 提交卖单并记录到日志
        val submitSellEvent = OrderSubmittedEvent(
            eventId = 1L,
            timestamp = Instant.now().toEpochMilli(),
            order = sellOrder
        )
        
        // 创建交易并记录到日志
        val trade = Trade(
            id = 1L,
            instrumentId = "XRP-USDT",
            price = 50L,
            quantity = 500L,
            buyOrderId = 1L,
            sellOrderId = 2L,
            buyUserId = 1L,
            sellUserId = 1L,
            timestamp = Instant.now().toEpochMilli(),
            makerOrderId = 1L,
            takerOrderId = 2L,
            buyFee = 0L,
            sellFee = 0L
        )
        
        val tradeEvent = TradeCreatedEvent(
            eventId = 2L,
            timestamp = Instant.now().toEpochMilli(),
            trade = trade
        )
        
        // 更新买单状态
        val updatedBuyOrder = buyOrder.copy(
            remainingQuantity = 500L,
            status = OrderStatus.PARTIALLY_FILLED
        )
        
        val updateBuyEvent = OrderSubmittedEvent(
            eventId = 3L,
            timestamp = Instant.now().toEpochMilli(),
            order = updatedBuyOrder
        )
        
        // 更新卖单状态
        val updatedSellOrder = sellOrder.copy(
            remainingQuantity = 0L,
            status = OrderStatus.FILLED
        )
        
        val updateSellEvent = OrderSubmittedEvent(
            eventId = 4L,
            timestamp = Instant.now().toEpochMilli(),
            order = updatedSellOrder
        )
        
        journalService.journal(submitSellEvent)
        journalService.journal(tradeEvent)
        journalService.journal(updateBuyEvent)
        journalService.journal(updateSellEvent)
        
        // 清空订单簿，模拟系统重启
        orderBookManager.shutdown()
        orderBookManager = OrderBookManager(maxInstruments = 10, snapshotIntervalMs = 1000, enableInternalSnapshots = false)
        
        // 创建新的恢复服务
        recoveryService = RecoveryService(
            journalService = journalService,
            orderBookManager = orderBookManager
        )
        
        // 执行 - 恢复系统
        val recoveryStats = recoveryService.recover()
        
        // 验证 - 检查恢复结果
        assertTrue(recoveryStats.eventsApplied >= 4, "应该应用至少4个事件")
    }
    
    @Test
    fun `test recovery with events before snapshot`() {
        // 准备 - 模拟事件发生在快照之前的情况
        val dotOrderBook = orderBookManager.getOrderBook("DOT-USDT")
        
        // 添加初始订单
        val order1 = createOrder(1L, "DOT-USDT", 10L, 100L, OrderSide.BUY)
        
        // 记录订单到日志
        val submitEvent = OrderSubmittedEvent(
            eventId = 1L,
            timestamp = Instant.now().toEpochMilli() - 30000, // 30秒前
            order = order1
        )
        
        journalService.journal(submitEvent)
        
        // 添加订单到订单簿并创建快照
        dotOrderBook.addOrder(order1)
        val snapshot = dotOrderBook.getSnapshot(10)
        journalService.saveSnapshot("DOT-USDT", snapshot)
        
        // 在快照后添加新订单
        val order2 = createOrder(2L, "DOT-USDT", 11L, 50L, OrderSide.BUY)
        val submitEvent2 = OrderSubmittedEvent(
            eventId = 2L,
            timestamp = Instant.now().toEpochMilli(),
            order = order2
        )
        
        journalService.journal(submitEvent2)
        
        // 清空订单簿，模拟系统重启
        orderBookManager.shutdown()
        orderBookManager = OrderBookManager(maxInstruments = 10, snapshotIntervalMs = 1000, enableInternalSnapshots = false)
        
        // 创建新的恢复服务，启用包含快照前事件
        recoveryService = RecoveryService(
            journalService = journalService,
            orderBookManager = orderBookManager,
            config = RecoveryConfig(
                includeEventsBeforeSnapshot = true,
                eventsBeforeSnapshotTimeWindowMs = 60000 // 允许60秒前的事件
            )
        )
        
        // 执行 - 恢复系统
        val recoveryStats = recoveryService.recover()
        
        // 验证 - 检查恢复结果
        assertTrue(recoveryStats.eventsApplied >= 2, "应该应用至少2个事件（快照前后各一个）")
        
        // 验证订单簿状态
        val recoveredOrderBook = orderBookManager.getOrderBook("DOT-USDT")
        
        // 验证最佳买单
        val recoveredSnapshot = recoveredOrderBook.getSnapshot(10)
        assertFalse(recoveredSnapshot.bids.isEmpty(), "应该有买单")
        assertEquals(11L, recoveredSnapshot.bids[0].price, "最佳买单价格应该是11")
    }
    
    // 辅助方法 - 创建订单
    private fun createOrder(
        id: Long,
        instrumentId: String,
        price: Number,
        quantity: Long,
        side: OrderSide
    ): Order {
        return Order(
            id = id,
            userId = 1L,
            instrumentId = instrumentId,
            price = price.toLong(),
            quantity = quantity,
            remainingQuantity = quantity,
            side = side,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = Instant.now().toEpochMilli()
        )
    }
} 