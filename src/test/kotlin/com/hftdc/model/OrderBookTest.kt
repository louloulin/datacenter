package com.hftdc.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

class OrderBookTest {

    private lateinit var orderBook: OrderBook
    
    @BeforeEach
    fun setUp() {
        orderBook = OrderBook("BTC-USDT")
    }
    
    @Test
    fun `test basic limit order matching`() {
        // 添加一个买单
        val buyOrder = createOrder(1L, 1L, 50000L, 1L, OrderSide.BUY)
        val buyResult = orderBook.addOrder(buyOrder)
        assertTrue(buyResult.isEmpty())
        
        // 添加一个卖单，应该立即匹配
        val sellOrder = createOrder(2L, 2L, 50000L, 1L, OrderSide.SELL)
        val sellResult = orderBook.addOrder(sellOrder)
        
        // 验证匹配结果
        assertEquals(1, sellResult.size)
        assertEquals(50000L, sellResult[0].price)
        assertEquals(1L, sellResult[0].quantity)
    }
    
    @Test
    fun `test POST_ONLY order that would not match`() {
        // 添加一个买单
        val buyOrder = createOrder(1L, 1L, 50000L, 1L, OrderSide.BUY)
        orderBook.addOrder(buyOrder)
        
        // 添加一个POST_ONLY卖单，价格高于买单，不会立即成交
        val sellOrder = createOrder(2L, 2L, 60000L, 1L, OrderSide.SELL, OrderType.POST_ONLY)
        val result = orderBook.addOrder(sellOrder)
        
        // 验证未匹配但成功添加
        assertTrue(result.isEmpty())
        assertEquals(OrderStatus.NEW, orderBook.getOrder(sellOrder.id)?.status)
    }
    
    @Test
    fun `test POST_ONLY order that would match and get rejected`() {
        // 添加一个买单
        val buyOrder = createOrder(1L, 1L, 50000L, 1L, OrderSide.BUY)
        orderBook.addOrder(buyOrder)
        
        // 添加一个POST_ONLY卖单，价格等于买单，会立即成交，应被拒绝
        val sellOrder = createOrder(2L, 2L, 50000L, 1L, OrderSide.SELL, OrderType.POST_ONLY)
        val result = orderBook.addOrder(sellOrder)
        
        // 验证匹配结果
        assertTrue(result.isEmpty())
        assertEquals(OrderStatus.REJECTED, orderBook.getOrder(sellOrder.id)?.status)
    }
    
    @Test
    fun `test FOK order that can be fully filled`() {
        // 添加一个卖单
        val sellOrder = createOrder(1L, 1L, 50000L, 2L, OrderSide.SELL)
        orderBook.addOrder(sellOrder)
        
        // 添加一个FOK买单，数量小于等于卖单，应该完全成交
        val buyOrder = createOrder(2L, 2L, 50000L, 1L, OrderSide.BUY, OrderType.FOK)
        val result = orderBook.addOrder(buyOrder)
        
        // 验证匹配结果
        assertEquals(1, result.size)
        assertEquals(50000L, result[0].price)
        assertEquals(1L, result[0].quantity)
        assertEquals(OrderStatus.FILLED, orderBook.getOrder(buyOrder.id)?.status)
    }
    
    @Test
    fun `test FOK order that cannot be fully filled`() {
        // 添加一个卖单
        val sellOrder = createOrder(1L, 1L, 50000L, 1L, OrderSide.SELL)
        orderBook.addOrder(sellOrder)
        
        // 添加一个FOK买单，数量大于卖单，不能完全成交，应该被取消
        val buyOrder = createOrder(2L, 2L, 50000L, 2L, OrderSide.BUY, OrderType.FOK)
        val result = orderBook.addOrder(buyOrder)
        
        // 验证匹配结果
        assertTrue(result.isEmpty())
        assertEquals(OrderStatus.CANCELED, orderBook.getOrder(buyOrder.id)?.status)
    }
    
    @Test
    fun `test IOC order that can be partially filled`() {
        // 添加一个卖单
        val sellOrder = createOrder(1L, 1L, 50000L, 1L, OrderSide.SELL)
        orderBook.addOrder(sellOrder)
        
        // 添加一个IOC买单，数量大于卖单，应该部分成交，剩余取消
        val buyOrder = createOrder(2L, 2L, 50000L, 2L, OrderSide.BUY, OrderType.IOC)
        val result = orderBook.addOrder(buyOrder)
        
        // 验证匹配结果
        assertEquals(1, result.size)
        assertEquals(50000L, result[0].price)
        assertEquals(1L, result[0].quantity)
        assertEquals(OrderStatus.CANCELED, orderBook.getOrder(buyOrder.id)?.status)
        assertEquals(1L, orderBook.getOrder(buyOrder.id)?.filledQuantity)
    }
    
    @Test
    fun `test IOC order that cannot be filled`() {
        // 添加一个卖单，价格高于IOC买单
        val sellOrder = createOrder(1L, 1L, 51000L, 1L, OrderSide.SELL)
        orderBook.addOrder(sellOrder)
        
        // 添加一个IOC买单，价格低于卖单，不能成交，应该被取消
        val buyOrder = createOrder(2L, 2L, 50000L, 1L, OrderSide.BUY, OrderType.IOC)
        val result = orderBook.addOrder(buyOrder)
        
        // 验证匹配结果
        assertTrue(result.isEmpty())
        assertEquals(OrderStatus.CANCELED, orderBook.getOrder(buyOrder.id)?.status)
    }
    
    @Test
    fun `test market order when no opposite orders exist`() {
        // 添加一个市价买单，但没有卖单
        val buyOrder = createOrder(1L, 1L, null, 1L, OrderSide.BUY, OrderType.MARKET)
        val result = orderBook.addOrder(buyOrder)
        
        // 验证没有匹配
        assertTrue(result.isEmpty())
    }
    
    @Test
    fun `test market order matching`() {
        // 添加一个卖单
        val sellOrder = createOrder(1L, 1L, 50000L, 1L, OrderSide.SELL)
        orderBook.addOrder(sellOrder)
        
        // 添加一个市价买单
        val buyOrder = createOrder(2L, 2L, null, 1L, OrderSide.BUY, OrderType.MARKET)
        val result = orderBook.addOrder(buyOrder)
        
        // 验证匹配结果
        assertEquals(1, result.size)
        assertEquals(50000L, result[0].price)
        assertEquals(1L, result[0].quantity)
    }
    
    // Helper method to create orders
    private fun createOrder(
        id: Long,
        userId: Long,
        price: Long?,
        quantity: Long,
        side: OrderSide,
        type: OrderType = OrderType.LIMIT,
        timeInForce: TimeInForce = TimeInForce.GTC
    ): Order {
        return Order(
            id = id,
            userId = userId,
            instrumentId = "BTC-USDT",
            price = price,
            quantity = quantity,
            remainingQuantity = quantity,
            side = side,
            type = type,
            timeInForce = timeInForce,
            status = OrderStatus.NEW,
            timestamp = Instant.now().toEpochMilli()
        )
    }
} 