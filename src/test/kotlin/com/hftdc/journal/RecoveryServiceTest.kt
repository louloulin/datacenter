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
        val now = Instant.now().toEpochMilli()
        val snapshotTime = now - 1000 // 快照时间是当前时间前1秒
        
        // 1. 创建并保存快照
        val snapshot = OrderBookSnapshot(
            instrumentId = instrumentId,
            bids = listOf(OrderBookLevel(price = 1000, quantity = 5)),
            asks = listOf(OrderBookLevel(price = 1100, quantity = 3)),
            timestamp = snapshotTime
        )
        journalService.saveSnapshot(instrumentId, snapshot)
        
        // 获取快照后的实际时间戳
        val actualSnapshotTime = journalService.getLatestSnapshot(instrumentId)?.timestamp ?: 0L
        println("实际快照时间戳: $actualSnapshotTime")
        
        // 2. 创建快照之后的新订单事件 - 确保事件时间戳在快照之后
        val eventTime = actualSnapshotTime + 100 // 确保事件时间戳在快照之后
        println("事件时间戳: $eventTime (比快照晚 ${eventTime - actualSnapshotTime} ms)")
        
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
            timestamp = eventTime // 使用确保在快照后的时间
        )
        
        val orderEvent = OrderSubmittedEvent(
            eventId = 1L,
            timestamp = eventTime, // 使用确保在快照后的时间
            order = newOrder
        )
        
        println("添加 OrderSubmittedEvent: orderId=${newOrder.id}, instrumentId=${newOrder.instrumentId}, timestamp=${orderEvent.timestamp}")
        journalService.journal(orderEvent)
        
        // 验证事件是否已添加到日志
        val events = journalService.getEvents(
            instrumentId = instrumentId,
            startTime = actualSnapshotTime,
            endTime = now + 10000, // 确保足够大的时间范围
            types = setOf(EventType.ORDER_SUBMITTED)
        )
        
        println("事件检查 - 事件数量: ${events.size}")
        events.forEach { event ->
            println("事件: type=${event.type}, timestamp=${event.timestamp}, eventId=${event.eventId}")
            if (event is OrderSubmittedEvent) {
                println("  订单ID: ${event.order.id}, 价格: ${event.order.price}")
            }
        }
        
        // 确保事件已添加到日志中，否则测试无法继续
        if (events.isEmpty()) {
            fail("事件未被正确地添加到日志中，测试无法继续")
        }
        
        // 3. 执行恢复
        recoveryService.recover()
        
        // 4. 验证 - 检查订单簿中是否包含快照数据和后续事件
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        
        // 获取所有订单并检查是否包含后续添加的订单
        val allOrders = orderBook.getAllOrders()
        
        println("所有订单: ${allOrders.size}")
        allOrders.forEach { order ->
            println("订单: id=${order.id}, price=${order.price}, side=${order.side}, isPlaceholder=${order.isPlaceholder}")
        }
        
        val matchingOrders = allOrders.filter { 
            !it.isPlaceholder && it.price == 1050L && it.id == 1L
        }
        
        println("匹配订单: ${matchingOrders.size}")
        
        if (matchingOrders.isEmpty()) {
            fail("订单簿中应该包含后续添加的ID为1的订单，但没有找到。所有订单: ${allOrders.map { "${it.id}(placeholder=${it.isPlaceholder})" }}")
        }
    }
    
    @Test
    fun `test recovery with order cancellation events`() {
        val instrumentId = "LTC-USDT"
        val now = Instant.now().toEpochMilli()
        val snapshotTime = now - 2000 // 快照时间是当前时间前2秒
        
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
            timestamp = snapshotTime
        )
        journalService.saveSnapshot(instrumentId, snapshot)
        
        // 获取快照后的实际时间戳
        val actualSnapshotTime = journalService.getLatestSnapshot(instrumentId)?.timestamp ?: 0L
        println("实际快照时间戳: $actualSnapshotTime")
        
        // 2. 创建一个订单和取消事件 - 确保事件时间戳在快照之后
        val orderTime = actualSnapshotTime + 100
        val cancelTime = orderTime + 100
        println("订单时间戳: $orderTime (比快照晚 ${orderTime - actualSnapshotTime} ms)")
        println("取消时间戳: $cancelTime (比订单晚 ${cancelTime - orderTime} ms)")
        
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
            timestamp = orderTime
        )
        
        val orderEvent = OrderSubmittedEvent(
            eventId = 100L,
            timestamp = orderTime,
            order = order
        )
        
        val cancelEvent = OrderCanceledEvent(
            eventId = 101L,
            timestamp = cancelTime,
            orderId = 100L,
            instrumentId = instrumentId,
            reason = "测试取消"
        )
        
        // 添加订单和取消事件到日志
        println("添加 OrderSubmittedEvent: orderId=${order.id}, instrumentId=${order.instrumentId}")
        journalService.journal(orderEvent)
        
        println("添加 OrderCanceledEvent: orderId=${cancelEvent.orderId}, instrumentId=${cancelEvent.instrumentId}")
        journalService.journal(cancelEvent)
        
        // 验证事件是否已添加到日志
        val orderEvents = journalService.getEvents(
            instrumentId = instrumentId,
            startTime = actualSnapshotTime,
            endTime = now + 10000,
            types = setOf(EventType.ORDER_SUBMITTED)
        )
        
        val cancelEvents = journalService.getEvents(
            instrumentId = instrumentId,
            startTime = actualSnapshotTime,
            endTime = now + 10000,
            types = setOf(EventType.ORDER_CANCELED)
        )
        
        println("事件检查 - 订单事件数量: ${orderEvents.size}, 取消事件数量: ${cancelEvents.size}")
        
        // 确保事件已添加到日志中，否则测试无法继续
        if (orderEvents.isEmpty() || cancelEvents.isEmpty()) {
            fail("事件未被正确地添加到日志中, 订单事件: ${orderEvents.size}, 取消事件: ${cancelEvents.size}")
        }
        
        // 3. 执行恢复
        recoveryService.recover()
        
        // 4. 验证 - 检查订单是否被正确还原和取消
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        val allOrders = orderBook.getAllOrders()
        
        println("所有订单: ${allOrders.size}")
        allOrders.forEach { order ->
            println("订单: id=${order.id}, price=${order.price}, side=${order.side}, isPlaceholder=${order.isPlaceholder}, status=${order.status}")
        }
        
        // 找出ID为100的非占位订单
        val targetOrders = allOrders.filter { 
            !it.isPlaceholder && it.id == 100L 
        }
        
        // 验证我们能找到这个订单
        if (targetOrders.isEmpty()) {
            fail("应该能找到ID为100的订单，但没有找到。所有订单: ${allOrders.map { it.id }}")
        }
        
        val canceledOrder = targetOrders.first()
        assertEquals(OrderStatus.CANCELED, canceledOrder.status, "订单应该是已取消状态，但实际状态是${canceledOrder.status}")
    }
    
    @Test
    fun `test recovery with events exactly at snapshot timestamp`() {
        val instrumentId = "XRP-USDT"
        
        // 1. 创建一个快照
        val snapshotTimestamp = Instant.now().toEpochMilli()
        val snapshot = OrderBookSnapshot(
            instrumentId = instrumentId,
            bids = listOf(OrderBookLevel(price = 200, quantity = 10)),
            asks = listOf(OrderBookLevel(price = 210, quantity = 10)),
            timestamp = snapshotTimestamp
        )
        journalService.saveSnapshot(instrumentId, snapshot)
        
        // 获取保存后的快照时间戳
        val actualSnapshotTime = journalService.getLatestSnapshot(instrumentId)?.timestamp ?: 0L
        println("实际快照时间戳: $actualSnapshotTime")
        
        // 2. 创建与快照时间戳完全相同的事件
        val order = Order(
            id = 200L,
            userId = 789L,
            instrumentId = instrumentId,
            price = 205L,
            quantity = 5L,
            remainingQuantity = 5L,
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = actualSnapshotTime // 使用完全相同的时间戳
        )
        
        val orderEvent = OrderSubmittedEvent(
            eventId = 200L,
            timestamp = actualSnapshotTime, // 使用完全相同的时间戳
            order = order
        )
        
        journalService.journal(orderEvent)
        
        // 验证事件是否已添加到日志
        val events = journalService.getEvents(
            instrumentId = instrumentId,
            startTime = actualSnapshotTime, // 包含与快照相同时间戳的事件
            endTime = actualSnapshotTime + 10000,
            types = setOf(EventType.ORDER_SUBMITTED)
        )
        
        println("同时间戳事件检查 - 事件数量: ${events.size}")
        events.forEach { event ->
            println("事件: type=${event.type}, timestamp=${event.timestamp}, eventId=${event.eventId}")
        }
        
        assertTrue(events.isNotEmpty(), "应该能找到与快照时间戳相同的事件")
        
        // 3. 执行恢复
        recoveryService.recover()
        
        // 4. 验证 - 检查与快照时间戳相同的订单是否被恢复
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        val allOrders = orderBook.getAllOrders()
        
        println("所有订单: ${allOrders.size}")
        allOrders.forEach { order ->
            println("订单: id=${order.id}, price=${order.price}, timestamp=${order.timestamp}")
        }
        
        // 检查ID为200的订单是否被恢复
        val recoveredOrder = allOrders.find { !it.isPlaceholder && it.id == 200L }
        
        assertNotNull(recoveredOrder, "与快照时间戳相同的订单应该被恢复")
        assertEquals(205L, recoveredOrder?.price, "恢复的订单价格应该是205")
    }
    
    @Test
    fun `test recovery with events before snapshot timestamp`() {
        val instrumentId = "DOT-USDT"
        val now = Instant.now().toEpochMilli()
        
        // 1. 创建事件，时间戳设置为当前时间
        val eventTime = now
        val order = Order(
            id = 300L,
            userId = 999L,
            instrumentId = instrumentId,
            price = 300L,
            quantity = 3L,
            remainingQuantity = 3L,
            side = OrderSide.SELL,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = eventTime
        )
        
        val orderEvent = OrderSubmittedEvent(
            eventId = 300L,
            timestamp = eventTime,
            order = order
        )
        
        println("添加 OrderSubmittedEvent: orderId=${order.id}, instrumentId=${order.instrumentId}, timestamp=${orderEvent.timestamp}")
        journalService.journal(orderEvent)
        
        // 2. 创建快照，时间戳设置为比事件晚100毫秒
        val snapshotTime = eventTime + 100
        println("事件时间戳: $eventTime")
        println("快照时间戳: $snapshotTime (比事件晚 ${snapshotTime - eventTime} ms)")
        
        val snapshot = OrderBookSnapshot(
            instrumentId = instrumentId,
            bids = listOf(OrderBookLevel(price = 290, quantity = 5)),
            asks = listOf(OrderBookLevel(price = 310, quantity = 5)),
            timestamp = snapshotTime
        )
        journalService.saveSnapshot(instrumentId, snapshot)
        
        // 获取实际的快照时间戳
        val actualSnapshotTime = journalService.getLatestSnapshot(instrumentId)?.timestamp ?: 0L
        println("实际快照时间戳: $actualSnapshotTime")
        
        // 验证事件是否已添加到日志
        val eventsBeforeSnapshot = journalService.getEvents(
            instrumentId = instrumentId,
            startTime = 0,
            endTime = actualSnapshotTime - 1, // 排除快照时间
            types = setOf(EventType.ORDER_SUBMITTED)
        )
        
        println("快照前事件检查 - 事件数量: ${eventsBeforeSnapshot.size}")
        eventsBeforeSnapshot.forEach { event ->
            println("事件: type=${event.type}, timestamp=${event.timestamp}, eventId=${event.eventId}")
        }
        
        // 确保我们有快照前的事件
        if (eventsBeforeSnapshot.isEmpty()) {
            fail("应该有快照前的事件，但没有找到任何事件。事件时间戳: $eventTime, 快照时间戳: $actualSnapshotTime")
        }
        
        // 3. 执行恢复
        recoveryService.recover()
        
        // 4. 验证 - 检查快照前的订单是否被恢复
        // 注意：根据恢复逻辑，快照之前的事件应该会被忽略，因为系统状态已经包含在快照中
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        val allOrders = orderBook.getAllOrders()
        
        println("所有订单: ${allOrders.size}")
        allOrders.forEach { order ->
            println("订单: id=${order.id}, price=${order.price}, timestamp=${order.timestamp}, isPlaceholder=${order.isPlaceholder}")
        }
        
        // 正常情况下，我们不应该找到ID为300的订单，因为它是在快照之前的事件
        val preSnapshotOrder = allOrders.find { !it.isPlaceholder && it.id == 300L }
        
        // 此断言根据实际需求可能需要调整
        // 如果系统设计为忽略快照前的事件，则应该为null；如果设计为包含快照前的事件，则不应该为null
        if (preSnapshotOrder != null) {
            fail("快照前的订单不应该被恢复，但找到了ID为300的订单：$preSnapshotOrder")
        }
        
        // 验证快照中的价格层级是否恢复
        val bidOrders = allOrders.filter { it.side == OrderSide.BUY && it.price == 290L }
        val askOrders = allOrders.filter { it.side == OrderSide.SELL && it.price == 310L }
        
        if (bidOrders.isEmpty()) {
            fail("快照中的买单价格层级应该被恢复，但没有找到。所有订单: ${allOrders.map { "${it.id}(${it.side}, ${it.price})" }}")
        }
        if (askOrders.isEmpty()) {
            fail("快照中的卖单价格层级应该被恢复，但没有找到。所有订单: ${allOrders.map { "${it.id}(${it.side}, ${it.price})" }}")
        }
    }
    
    @Test
    fun `test recovery with events before snapshot timestamp with includeEventsBeforeSnapshot enabled`() {
        val instrumentId = "DOGE-USDT"
        val now = Instant.now().toEpochMilli()
        
        // 1. 创建事件，时间戳设置为当前时间
        val eventTime = now
        val order = Order(
            id = 500L,
            userId = 777L,
            instrumentId = instrumentId,
            price = 400L,
            quantity = 8L,
            remainingQuantity = 8L,
            side = OrderSide.SELL,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = eventTime
        )
        
        val orderEvent = OrderSubmittedEvent(
            eventId = 500L,
            timestamp = eventTime,
            order = order
        )
        
        journalService.journal(orderEvent)
        
        // 2. 创建快照，时间戳设置为比事件晚200毫秒
        val snapshotTime = eventTime + 200
        println("事件时间戳: $eventTime")
        println("快照时间戳: $snapshotTime (比事件晚 ${snapshotTime - eventTime} ms)")
        
        val snapshot = OrderBookSnapshot(
            instrumentId = instrumentId,
            bids = listOf(OrderBookLevel(price = 390, quantity = 5)),
            asks = listOf(OrderBookLevel(price = 410, quantity = 5)),
            timestamp = snapshotTime
        )
        journalService.saveSnapshot(instrumentId, snapshot)
        
        // 获取实际的快照时间戳
        val actualSnapshotTime = journalService.getLatestSnapshot(instrumentId)?.timestamp ?: 0L
        println("实际快照时间戳: $actualSnapshotTime")
        
        // 验证事件是否已添加到日志
        val eventsBeforeSnapshot = journalService.getEvents(
            instrumentId = instrumentId,
            startTime = 0,
            endTime = actualSnapshotTime - 1, // 排除快照时间
            types = setOf(EventType.ORDER_SUBMITTED)
        )
        
        println("快照前事件检查 - 事件数量: ${eventsBeforeSnapshot.size}")
        eventsBeforeSnapshot.forEach { event ->
            println("事件: type=${event.type}, timestamp=${event.timestamp}, eventId=${event.eventId}")
        }
        
        // 确保我们有快照前的事件
        assertTrue(eventsBeforeSnapshot.isNotEmpty(), "应该有快照前的事件")
        
        // 3. 创建一个启用了includeEventsBeforeSnapshot的恢复服务
        val recoveryConfig = RecoveryConfig(
            includeEventsBeforeSnapshot = true,
            eventsBeforeSnapshotTimeWindowMs = 5000 // 5秒
        )
        val recoveryServiceWithBeforeEvents = RecoveryService(
            journalService = journalService,
            orderBookManager = orderBookManager,
            config = recoveryConfig
        )
        
        // 4. 执行恢复
        recoveryServiceWithBeforeEvents.recover()
        
        // 5. 验证 - 检查快照前的订单应该被恢复
        val orderBook = orderBookManager.getOrderBook(instrumentId)
        val allOrders = orderBook.getAllOrders()
        
        println("所有订单: ${allOrders.size}")
        allOrders.forEach { order ->
            println("订单: id=${order.id}, price=${order.price}, timestamp=${order.timestamp}, isPlaceholder=${order.isPlaceholder}")
        }
        
        // 现在，启用了includeEventsBeforeSnapshot，我们应该能找到ID为500的订单
        val preSnapshotOrder = allOrders.find { !it.isPlaceholder && it.id == 500L }
        
        assertNotNull(preSnapshotOrder, "启用includeEventsBeforeSnapshot后，快照前的订单应该被恢复")
        assertEquals(400L, preSnapshotOrder?.price, "恢复的订单价格应该是400")
        assertEquals(OrderSide.SELL, preSnapshotOrder?.side, "恢复的订单方向应该是SELL")
        
        // 验证快照中的价格层级也被恢复
        val bidOrders = allOrders.filter { it.side == OrderSide.BUY && it.price == 390L }
        val askOrders = allOrders.filter { it.side == OrderSide.SELL && it.price == 410L }
        
        assertTrue(bidOrders.isNotEmpty(), "快照中的买单价格层级应该被恢复")
        assertTrue(askOrders.isNotEmpty(), "快照中的卖单价格层级应该被恢复")
    }
} 