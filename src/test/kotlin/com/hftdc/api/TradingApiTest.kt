package com.hftdc.api

import com.hftdc.core.ActorSystemManager
import com.hftdc.core.SystemStatus
import com.hftdc.engine.OrderBookManager
import com.hftdc.model.*
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.concurrent.CompletableFuture

class TradingApiTest {

    @MockK
    lateinit var rootActor: akka.actor.typed.ActorRef<com.hftdc.core.RootCommand>
    
    @MockK
    lateinit var orderBookManager: OrderBookManager
    
    @MockK
    lateinit var orderBook: OrderBook
    
    lateinit var tradingApi: TradingApi
    
    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        tradingApi = TradingApi(rootActor, orderBookManager)
        
        // Mock the OrderBookManager behavior
        every { orderBookManager.getActiveInstrumentIds() } returns setOf("BTC-USDT", "ETH-USDT")
        every { orderBookManager.getOrderBook(any()) } returns orderBook
        every { orderBookManager.hasInstrument(any()) } returns true
        every { orderBookManager.getOrderBookCount() } returns 2
        every { orderBookManager.getTotalOrderCount() } returns 10
    }
    
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
    
    @Test
    fun `test placeOrder success`() {
        // Arrange
        val request = TradingApi.PlaceOrderRequest(
            userId = 1L,
            instrumentId = "BTC-USDT",
            price = 50000L,
            quantity = 1L,
            side = OrderSide.BUY,
            type = OrderType.LIMIT
        )
        
        every { rootActor.tell(any()) } just Runs
        
        // Act
        val futureResponse = tradingApi.placeOrder(request)
        val response = futureResponse.toCompletableFuture().get()
        
        // Assert
        assertNotNull(response)
        assertEquals(OrderStatus.NEW, response.status)
        verify(exactly = 1) { rootActor.tell(any()) }
    }
    
    @Test
    fun `test cancelOrder when order exists`() {
        // Arrange
        val orderId = 1L
        val userId = 1L
        val order = Order(
            id = orderId,
            userId = userId,
            instrumentId = "BTC-USDT",
            price = 50000L,
            quantity = 1L,
            remainingQuantity = 1L,
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = Instant.now().toEpochMilli()
        )
        
        every { orderBook.getOrder(orderId) } returns order
        every { orderBook.cancelOrder(orderId) } returns order.copy(status = OrderStatus.CANCELED)
        
        // Act
        val futureResponse = tradingApi.cancelOrder(TradingApi.CancelOrderRequest(userId, orderId))
        val response = futureResponse.toCompletableFuture().get()
        
        // Assert
        assertTrue(response.success)
        verify(exactly = 1) { orderBook.cancelOrder(orderId) }
    }
    
    @Test
    fun `test cancelOrder when order does not exist`() {
        // Arrange
        val orderId = 999L
        val userId = 1L
        
        every { orderBook.getOrder(any()) } returns null
        
        // Act
        val futureResponse = tradingApi.cancelOrder(TradingApi.CancelOrderRequest(userId, orderId))
        val response = futureResponse.toCompletableFuture().get()
        
        // Assert
        assertFalse(response.success)
        assertNotNull(response.message)
        verify(exactly = 0) { orderBook.cancelOrder(any()) }
    }
    
    @Test
    fun `test queryOrder when order exists`() {
        // Arrange
        val orderId = 1L
        val userId = 1L
        val order = Order(
            id = orderId,
            userId = userId,
            instrumentId = "BTC-USDT",
            price = 50000L,
            quantity = 1L,
            remainingQuantity = 1L,
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            status = OrderStatus.NEW,
            timestamp = Instant.now().toEpochMilli()
        )
        
        every { orderBook.getOrder(orderId) } returns order
        
        // Act
        val futureResponse = tradingApi.queryOrder(TradingApi.QueryOrderRequest(userId, orderId))
        val response = futureResponse.toCompletableFuture().get()
        
        // Assert
        assertNotNull(response)
        assertEquals(orderId, response?.id)
        assertEquals(userId, response?.userId)
    }
    
    @Test
    fun `test queryOrder when order does not exist`() {
        // Arrange
        val orderId = 999L
        val userId = 1L
        
        every { orderBook.getOrder(any()) } returns null
        
        // Act
        val futureResponse = tradingApi.queryOrder(TradingApi.QueryOrderRequest(userId, orderId))
        val response = futureResponse.toCompletableFuture().get()
        
        // Assert
        assertNull(response)
    }
    
    @Test
    fun `test getOrderBook`() {
        // Arrange
        val instrumentId = "BTC-USDT"
        val snapshot = OrderBookSnapshot(
            instrumentId = instrumentId,
            bids = listOf(OrderBookLevel(50000L, 1L)),
            asks = listOf(OrderBookLevel(51000L, 2L)),
            timestamp = Instant.now().toEpochMilli()
        )
        
        every { orderBook.getSnapshot(any()) } returns snapshot
        
        // Act
        val futureResponse = tradingApi.getOrderBook(TradingApi.OrderBookRequest(instrumentId))
        val response = futureResponse.toCompletableFuture().get()
        
        // Assert
        assertNotNull(response)
        assertEquals(instrumentId, response.instrumentId)
        assertEquals(1, response.bids.size)
        assertEquals(1, response.asks.size)
    }
    
    @Test
    fun `test getSystemStatus`() {
        // Act
        val futureResponse = tradingApi.getSystemStatus()
        val response = futureResponse.toCompletableFuture().get()
        
        // Assert
        assertNotNull(response)
        assertEquals(2, response.orderBookCount)
        assertEquals(10, response.totalOrderCount)
        assertEquals(2, response.activeInstruments.size)
    }
    
    // Helper extension function to make testing easier
    private fun <T> CompletableFuture<T>.get(): T = this.get()
} 