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

class SnapshotManagerTest {

    private lateinit var journalService: InMemoryJournalService
    private lateinit var orderBookManager: OrderBookManager
    private lateinit var snapshotManager: SnapshotManager
    
    @BeforeEach
    fun setUp() {
        journalService = InMemoryJournalService()
        orderBookManager = OrderBookManager(maxInstruments = 10, snapshotIntervalMs = 1000, enableInternalSnapshots = false)
        snapshotManager = SnapshotManager(
            journalService = journalService,
            orderBookManager = orderBookManager,
            snapshotIntervalMs = 100, // 设置较短的间隔，便于测试
            initialDelayMs = 50
        )
    }
    
    @AfterEach
    fun tearDown() {
        snapshotManager.stop()
        orderBookManager.shutdown()
        journalService.shutdown()
    }
    
    @Test
    fun `test manual snapshot creation`() {
        // 准备 - 创建几个订单簿并添加订单
        val btcOrderBook = orderBookManager.getOrderBook("BTC-USDT")
        val ethOrderBook = orderBookManager.getOrderBook("ETH-USDT")
        
        // 添加一些订单
        val btcOrder = Order(
            id = 1L,
            userId = 1L,
            instrumentId = "BTC-USDT",
            price = 50000L,
            quantity = 2L,
            remainingQuantity = 2L,
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = Instant.now().toEpochMilli()
        )
        
        val ethOrder = Order(
            id = 2L,
            userId = 1L,
            instrumentId = "ETH-USDT",
            price = 3000L,
            quantity = 5L,
            remainingQuantity = 5L,
            side = OrderSide.SELL,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = Instant.now().toEpochMilli()
        )
        
        btcOrderBook.addOrder(btcOrder)
        ethOrderBook.addOrder(ethOrder)
        
        // 执行 - 手动创建快照
        val btcSnapshotId = snapshotManager.createSnapshotFor("BTC-USDT")
        val ethSnapshotId = snapshotManager.createSnapshotFor("ETH-USDT")
        
        // 验证 - 检查是否成功创建快照
        assertNotNull(btcSnapshotId, "BTC-USDT snapshot should be created successfully")
        assertNotNull(ethSnapshotId, "ETH-USDT snapshot should be created successfully")
        
        // 检查快照计数器
        assertEquals(2, snapshotManager.getSnapshotCount(), "Snapshot counter should record 2 snapshots")
        
        // 验证快照内容
        val btcSnapshot = journalService.getLatestSnapshot("BTC-USDT")
        val ethSnapshot = journalService.getLatestSnapshot("ETH-USDT")
        
        assertNotNull(btcSnapshot, "BTC-USDT snapshot should exist")
        assertNotNull(ethSnapshot, "ETH-USDT snapshot should exist")
        
        assertEquals(1, btcSnapshot?.snapshot?.bids?.size, "BTC-USDT snapshot should contain one buy order")
        assertEquals(1, ethSnapshot?.snapshot?.asks?.size, "ETH-USDT snapshot should contain one sell order")
        
        assertEquals(50000L, btcSnapshot?.snapshot?.bids?.get(0)?.price, "BTC-USDT buy order price should be 50000")
        assertEquals(3000L, ethSnapshot?.snapshot?.asks?.get(0)?.price, "ETH-USDT sell order price should be 3000")
    }
    
    @Test
    fun `test automatic periodic snapshots`() {
        // 准备 - 创建订单簿并添加订单
        val ltcOrderBook = orderBookManager.getOrderBook("LTC-USDT")
        
        val ltcOrder = Order(
            id = 3L,
            userId = 2L,
            instrumentId = "LTC-USDT",
            price = 150L,
            quantity = 10L,
            remainingQuantity = 10L,
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = Instant.now().toEpochMilli()
        )
        
        ltcOrderBook.addOrder(ltcOrder)
        
        // 启动快照管理器，设置较短的间隔以便测试
        snapshotManager.start()
        
        // 使用CountDownLatch等待足够时间，让自动快照任务执行几次
        val latch = CountDownLatch(1)
        val waitTimeMs = 350L // 等待足够时间，让快照任务执行至少3次
        latch.await(waitTimeMs, TimeUnit.MILLISECONDS)
        
        // 停止快照管理器
        snapshotManager.stop()
        
        // 验证 - 检查是否生成了快照
        val ltcSnapshot = journalService.getLatestSnapshot("LTC-USDT")
        
        assertNotNull(ltcSnapshot, "LTC-USDT snapshot should exist")
        assertEquals(1, ltcSnapshot?.snapshot?.bids?.size, "LTC-USDT snapshot should contain one buy order")
        assertEquals(150L, ltcSnapshot?.snapshot?.bids?.get(0)?.price, "LTC-USDT buy order price should be 150")
        
        // 检查快照计数器
        assertTrue(snapshotManager.getSnapshotCount() >= 1, "Should generate at least one snapshot")
        
        println("Generated ${snapshotManager.getSnapshotCount()} snapshots")
    }
    
    @Test
    fun `test snapshot manager with multiple instruments`() {
        // 准备 - 创建多个订单簿
        val instruments = listOf("BTC-USDT", "ETH-USDT", "LTC-USDT", "XRP-USDT", "DOT-USDT")
        
        // 为每个品种创建订单簿和添加订单
        instruments.forEachIndexed { index, instrumentId ->
            val orderBook = orderBookManager.getOrderBook(instrumentId)
            
            // 添加买单
            val buyOrder = Order(
                id = (index * 2 + 1).toLong(),
                userId = 1L,
                instrumentId = instrumentId,
                price = (1000 * (index + 1)).toLong(),
                quantity = (index + 1).toLong(),
                remainingQuantity = (index + 1).toLong(),
                side = OrderSide.BUY,
                type = OrderType.LIMIT,
                timeInForce = TimeInForce.GTC,
                status = OrderStatus.NEW,
                timestamp = Instant.now().toEpochMilli()
            )
            
            // 添加卖单
            val sellOrder = Order(
                id = (index * 2 + 2).toLong(),
                userId = 2L,
                instrumentId = instrumentId,
                price = (1100 * (index + 1)).toLong(),
                quantity = (index + 2).toLong(),
                remainingQuantity = (index + 2).toLong(),
                side = OrderSide.SELL,
                type = OrderType.LIMIT,
                timeInForce = TimeInForce.GTC,
                status = OrderStatus.NEW,
                timestamp = Instant.now().toEpochMilli()
            )
            
            orderBook.addOrder(buyOrder)
            orderBook.addOrder(sellOrder)
        }
        
        // 执行 - 创建所有活跃品种的快照
        snapshotManager.createSnapshots()
        
        // 验证 - 检查是否为所有品种创建了快照
        assertEquals(instruments.size.toLong(), snapshotManager.getSnapshotCount(), "Should create one snapshot for each instrument")
        
        // 验证每个品种的快照内容
        instruments.forEachIndexed { index, instrumentId ->
            val snapshot = journalService.getLatestSnapshot(instrumentId)
            
            assertNotNull(snapshot, "$instrumentId snapshot should exist")
            assertEquals(1, snapshot?.snapshot?.bids?.size, "$instrumentId snapshot should contain one buy order")
            assertEquals(1, snapshot?.snapshot?.asks?.size, "$instrumentId snapshot should contain one sell order")
            
            assertEquals((1000 * (index + 1)).toLong(), snapshot?.snapshot?.bids?.get(0)?.price, "$instrumentId buy order price check")
            assertEquals((1100 * (index + 1)).toLong(), snapshot?.snapshot?.asks?.get(0)?.price, "$instrumentId sell order price check")
        }
    }
} 