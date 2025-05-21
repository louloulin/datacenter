package com.hftdc.market

import com.hftdc.engine.OrderBookManager
import com.hftdc.model.Order
import com.hftdc.model.OrderSide
import com.hftdc.model.OrderStatus
import com.hftdc.model.OrderType
import com.hftdc.model.TimeInForce
import com.hftdc.model.Trade
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MarketDataProcessorTest {
    
    private lateinit var orderBookManager: OrderBookManager
    private lateinit var marketDataProcessor: MarketDataProcessor
    
    @BeforeEach
    fun setUp() {
        orderBookManager = OrderBookManager(
            maxInstruments = 10,
            snapshotIntervalMs = 1000
        )
        
        marketDataProcessor = MarketDataProcessor(
            orderBookManager = orderBookManager,
            snapshotIntervalMs = 100 // 更快的快照频率，用于测试
        )
    }
    
    @Test
    fun `test market data publication on trade`() {
        // 创建一个测试订阅者
        val latch = CountDownLatch(1)
        val updateCounter = AtomicInteger(0)
        val testSubscription = MarketDataSubscription(
            instrumentId = "BTC-USDT",
            depth = 5,
            frequency = MarketDataFrequency.REALTIME
        )
        
        val testSubscriber = object : MarketDataSubscriber {
            override val subscription: MarketDataSubscription = testSubscription
            
            override fun onMarketDataUpdate(update: MarketDataUpdate) {
                updateCounter.incrementAndGet()
                if (update.updateType == MarketDataUpdateType.TRADE) {
                    assertEquals("BTC-USDT", update.instrumentId)
                    assertEquals(10000L, update.lastTradePrice)
                    assertEquals(1L, update.lastTradeQuantity)
                    latch.countDown()
                }
            }
            
            override fun onStatistics(statistics: MarketStatistics) {
                // 不测试统计数据
            }
        }
        
        // 订阅市场数据
        marketDataProcessor.subscribe(testSubscription, testSubscriber)
        
        // 创建并处理一笔交易
        val trade = Trade(
            id = 1L,
            buyOrderId = 1L,
            sellOrderId = 2L,
            buyUserId = 1L,
            sellUserId = 2L,
            instrumentId = "BTC-USDT",
            price = 10000L,
            quantity = 1L,
            timestamp = Instant.now().toEpochMilli(),
            makerOrderId = 1L,
            takerOrderId = 2L,
            buyFee = 10L,
            sellFee = 10L
        )
        
        // 模拟订单簿中添加订单
        val buyOrder = Order(
            id = 1L,
            userId = 1L,
            instrumentId = "BTC-USDT",
            price = 10000L,
            quantity = 1L,
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            timestamp = Instant.now().toEpochMilli(),
            status = OrderStatus.NEW
        )
        
        val sellOrder = Order(
            id = 2L,
            userId = 2L,
            instrumentId = "BTC-USDT",
            price = 10000L,
            quantity = 1L,
            side = OrderSide.SELL,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            timestamp = Instant.now().toEpochMilli(),
            status = OrderStatus.NEW
        )
        
        // 添加订单到订单簿
        orderBookManager.getOrderBook("BTC-USDT").addOrder(buyOrder)
        
        // 处理交易
        marketDataProcessor.onTrade(trade)
        
        // 验证是否收到了市场数据更新
        assertTrue(latch.await(5, TimeUnit.SECONDS), "没有在预期时间内收到交易市场数据更新")
        assertTrue(updateCounter.get() >= 1, "应该至少收到一次市场数据更新")
    }
    
    @Test
    fun `test market data snapshot publication`() {
        // 创建一个测试订阅者
        val latch = CountDownLatch(1)
        val testSubscription = MarketDataSubscription(
            instrumentId = "ETH-USDT",
            depth = 5,
            frequency = MarketDataFrequency.REALTIME
        )
        
        val testSubscriber = object : MarketDataSubscriber {
            override val subscription: MarketDataSubscription = testSubscription
            
            override fun onMarketDataUpdate(update: MarketDataUpdate) {
                if (update.updateType == MarketDataUpdateType.SNAPSHOT) {
                    assertEquals("ETH-USDT", update.instrumentId)
                    assertTrue(update.timestamp > 0)
                    latch.countDown()
                }
            }
            
            override fun onStatistics(statistics: MarketStatistics) {
                // 不测试统计数据
            }
        }
        
        // 订阅市场数据 - 应该立即收到一个快照
        marketDataProcessor.subscribe(testSubscription, testSubscriber)
        
        // 验证是否收到了市场数据快照
        assertTrue(latch.await(5, TimeUnit.SECONDS), "没有在预期时间内收到市场数据快照")
    }
    
    @Test
    fun `test unsubscribe from market data`() {
        // 创建一个测试订阅者
        val updateCounter = AtomicInteger(0)
        val testSubscription = MarketDataSubscription(
            instrumentId = "LTC-USDT",
            depth = 5,
            frequency = MarketDataFrequency.REALTIME
        )
        
        val testSubscriber = object : MarketDataSubscriber {
            override val subscription: MarketDataSubscription = testSubscription
            
            override fun onMarketDataUpdate(update: MarketDataUpdate) {
                updateCounter.incrementAndGet()
            }
            
            override fun onStatistics(statistics: MarketStatistics) {
                // 不测试统计数据
            }
        }
        
        // 订阅市场数据
        marketDataProcessor.subscribe(testSubscription, testSubscriber)
        
        // 等待接收第一个更新
        Thread.sleep(200)
        
        // 记录当前更新计数
        val countBeforeUnsubscribe = updateCounter.get()
        assertTrue(countBeforeUnsubscribe >= 1, "应该至少收到一次市场数据更新")
        
        // 取消订阅
        marketDataProcessor.unsubscribe(testSubscription, testSubscriber)
        
        // 等待一段时间
        Thread.sleep(300)
        
        // 验证取消订阅后是否还收到更新
        assertEquals(countBeforeUnsubscribe, updateCounter.get(), "取消订阅后不应该收到更多更新")
    }
} 