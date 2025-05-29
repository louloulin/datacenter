package com.hftdc.metrics

import com.hftdc.model.Order
import com.hftdc.model.OrderSide
import com.hftdc.model.OrderStatus
import com.hftdc.model.OrderType
import com.hftdc.model.TimeInForce
import com.hftdc.model.Trade
import io.prometheus.client.CollectorRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.time.Instant

class OrderMetricsHandlerTest {

    private lateinit var orderMetricsHandler: OrderMetricsHandler
    
    @BeforeEach
    fun setUp() {
        // Don't clear the registry as it would invalidate the MetricsRegistry counters
        // Instead, just reinitialize the handler
        orderMetricsHandler = OrderMetricsHandler()
    }
    
    @Test
    fun `test record order received`() {
        // Given
        val order = createTestOrder(OrderStatus.NEW)
        val initialCount = MetricsRegistry.orderProcessedCounter
            .labels(order.instrumentId, order.side.name.lowercase(), order.type.name.lowercase())
            .get()
        
        // When
        orderMetricsHandler.recordOrderReceived(order)
        
        // Then
        val finalCount = MetricsRegistry.orderProcessedCounter
            .labels(order.instrumentId, order.side.name.lowercase(), order.type.name.lowercase())
            .get()
        assertEquals(initialCount + 1.0, finalCount, 0.001)
    }
    
    @Test
    fun `test record order executed`() {
        // Given
        val order = createTestOrder(OrderStatus.PARTIALLY_FILLED)
        val trades = listOf(
            createTestTrade(order, 5),
            createTestTrade(order, 3)
        )
        
        val initialExecutedCount = MetricsRegistry.orderExecutedCounter
            .labels(order.instrumentId, order.side.name.lowercase())
            .get()
        val initialTradeVolume = MetricsRegistry.tradeVolumeCounter
            .labels(order.instrumentId)
            .get()
        
        // First record order received to set start time
        orderMetricsHandler.recordOrderReceived(order)
        
        // When
        orderMetricsHandler.recordOrderExecuted(order, trades)
        
        // Then
        val finalExecutedCount = MetricsRegistry.orderExecutedCounter
            .labels(order.instrumentId, order.side.name.lowercase())
            .get()
        assertEquals(initialExecutedCount + 1.0, finalExecutedCount, 0.001)
        
        val finalTradeVolume = MetricsRegistry.tradeVolumeCounter
            .labels(order.instrumentId)
            .get()
        assertEquals(initialTradeVolume + 8.0, finalTradeVolume, 0.001) // 5 + 3 = 8
        
        // Verify that order processing time was recorded
        val histogram = MetricsRegistry.orderProcessingTimeHistogram
            .labels(order.instrumentId, order.type.name.lowercase())
        val sample = histogram.get()
        assertTrue(sample.sum > 0, "Histogram should have observations")
    }
    
    @Test
    fun `test record order cancelled`() {
        // Given
        val order = createTestOrder(OrderStatus.CANCELED)
        val initialCancelledCount = MetricsRegistry.ordersCancelledCounter
            .labels(order.instrumentId, order.side.name.lowercase())
            .get()
        
        // First record order received to set start time
        orderMetricsHandler.recordOrderReceived(order)
        
        // When
        orderMetricsHandler.recordOrderCancelled(order)
        
        // Then
        val finalCancelledCount = MetricsRegistry.ordersCancelledCounter
            .labels(order.instrumentId, order.side.name.lowercase())
            .get()
        assertEquals(initialCancelledCount + 1.0, finalCancelledCount, 0.001)
        
        // Verify that order processing time was recorded
        val histogram = MetricsRegistry.orderProcessingTimeHistogram
            .labels(order.instrumentId, order.type.name.lowercase())
        val sample = histogram.get()
        assertTrue(sample.sum > 0, "Histogram should have observations")
    }
    
    @Test
    fun `test record order completed`() {
        // Given
        val order = createTestOrder(OrderStatus.FILLED)
        
        // First record order received to set start time
        orderMetricsHandler.recordOrderReceived(order)
        
        // When
        orderMetricsHandler.recordOrderCompleted(order)
        
        // Then
        // Verify that order processing time was recorded
        val histogram = MetricsRegistry.orderProcessingTimeHistogram
            .labels(order.instrumentId, order.type.name.lowercase())
        val sample = histogram.get()
        assertTrue(sample.sum > 0, "Histogram should have observations")
    }
    
    @Test
    fun `test cleanup stale records`() {
        // Given
        val order1 = createTestOrder(OrderStatus.NEW, orderId = 1)
        val order2 = createTestOrder(OrderStatus.NEW, orderId = 2)
        
        // Manually add entries to the orderProcessingStartTimes map using reflection
        val startTimesField = OrderMetricsHandler::class.java.getDeclaredField("orderProcessingStartTimes")
        startTimesField.isAccessible = true
        val startTimes = startTimesField.get(orderMetricsHandler) as MutableMap<Long, Long>
        
        val now = Instant.now().toEpochMilli()
        startTimes[order1.id] = now - 3600001 // Just over 1 hour ago
        startTimes[order2.id] = now - 1000 // 1 second ago
        
        // When
        orderMetricsHandler.cleanupStaleRecords() // Default is 1 hour
        
        // Then
        assertFalse(startTimes.containsKey(order1.id), "Order1 should be removed as stale")
        assertTrue(startTimes.containsKey(order2.id), "Order2 should be kept as recent")
    }
    
    // Helper methods
    
    private fun createTestOrder(
        status: OrderStatus, 
        orderId: Long = 123L,
        instrumentId: String = "BTC-USDT"
    ): Order {
        return Order(
            id = orderId,
            userId = 456L,
            instrumentId = instrumentId,
            price = 50000L,
            quantity = 10L,
            remainingQuantity = 10L,
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            timestamp = Instant.now().toEpochMilli(),
            status = status
        )
    }
    
    private fun createTestTrade(order: Order, quantity: Long): Trade {
        val counterpartyOrderId = 555L
        val counterpartyUserId = 789L
        
        return Trade(
            id = 789L,
            buyOrderId = if (order.side == OrderSide.BUY) order.id else counterpartyOrderId,
            sellOrderId = if (order.side == OrderSide.SELL) order.id else counterpartyOrderId,
            buyUserId = if (order.side == OrderSide.BUY) order.userId else counterpartyUserId,
            sellUserId = if (order.side == OrderSide.SELL) order.userId else counterpartyUserId,
            instrumentId = order.instrumentId,
            price = order.price ?: 50000L,
            quantity = quantity,
            timestamp = Instant.now().toEpochMilli(),
            makerOrderId = counterpartyOrderId, // Assume counterparty is maker for simplicity
            takerOrderId = order.id, // Assume test order is taker
            buyFee = 10,
            sellFee = 10
        )
    }
}