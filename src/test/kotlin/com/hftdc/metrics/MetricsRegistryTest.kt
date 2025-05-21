package com.hftdc.metrics

import io.prometheus.client.CollectorRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class MetricsRegistryTest {

    @BeforeEach
    fun setUp() {
        // Clear registry before each test
        CollectorRegistry.defaultRegistry.clear()
        // Reinitialize the registry
        val metricsRegistry = MetricsRegistry
    }

    @Test
    fun `test order counters`() {
        // Given
        val instrumentId = "BTC-USDT"
        val side = "buy"
        val type = "limit"
        
        // When
        MetricsRegistry.recordOrderProcessed(instrumentId, side, type)
        MetricsRegistry.recordOrderExecuted(instrumentId, side)
        MetricsRegistry.recordTradeVolume(instrumentId, 10)
        
        // Then
        val orderProcessedCount = MetricsRegistry.orderProcessedCounter
            .labels(instrumentId, side, type)
            .get()
        assertEquals(1.0, orderProcessedCount, 0.001)
        
        val orderExecutedCount = MetricsRegistry.orderExecutedCounter
            .labels(instrumentId, side)
            .get()
        assertEquals(1.0, orderExecutedCount, 0.001)
        
        val tradeVolume = MetricsRegistry.tradeVolumeCounter
            .labels(instrumentId)
            .get()
        assertEquals(10.0, tradeVolume, 0.001)
    }
    
    @Test
    fun `test order book gauges`() {
        // Given
        val instrumentId = "BTC-USDT"
        val buySide = "buy"
        val sellSide = "sell"
        
        // When
        MetricsRegistry.setOrderBookDepth(instrumentId, buySide, 5)
        MetricsRegistry.setOrderBookDepth(instrumentId, sellSide, 7)
        MetricsRegistry.setOrdersInBook(instrumentId, buySide, 15)
        MetricsRegistry.setOrdersInBook(instrumentId, sellSide, 20)
        MetricsRegistry.setActiveInstruments(3)
        
        // Then
        val buyDepth = MetricsRegistry.orderBookDepthGauge
            .labels(instrumentId, buySide)
            .get()
        assertEquals(5.0, buyDepth, 0.001)
        
        val sellDepth = MetricsRegistry.orderBookDepthGauge
            .labels(instrumentId, sellSide)
            .get()
        assertEquals(7.0, sellDepth, 0.001)
        
        val buyOrders = MetricsRegistry.ordersInBookGauge
            .labels(instrumentId, buySide)
            .get()
        assertEquals(15.0, buyOrders, 0.001)
        
        val sellOrders = MetricsRegistry.ordersInBookGauge
            .labels(instrumentId, sellSide)
            .get()
        assertEquals(20.0, sellOrders, 0.001)
        
        val activeInstruments = MetricsRegistry.activeInstrumentsGauge.get()
        assertEquals(3.0, activeInstruments, 0.001)
    }
    
    @Test
    fun `test JVM metrics`() {
        // Given
        val heapType = "heap"
        val nonHeapType = "non-heap"
        val gcName = "G1 Young Generation"
        
        // When
        MetricsRegistry.setJvmMemoryUsage(heapType, 1024 * 1024 * 100) // 100MB
        MetricsRegistry.setJvmMemoryUsage(nonHeapType, 1024 * 1024 * 20) // 20MB
        MetricsRegistry.recordGcCollection(gcName, 5)
        MetricsRegistry.recordGcCollectionTime(gcName, 0.123)
        
        // Then
        val heapUsage = MetricsRegistry.jvmMemoryUsageGauge
            .labels(heapType)
            .get()
        assertEquals(1024.0 * 1024.0 * 100.0, heapUsage, 0.001)
        
        val nonHeapUsage = MetricsRegistry.jvmMemoryUsageGauge
            .labels(nonHeapType)
            .get()
        assertEquals(1024.0 * 1024.0 * 20.0, nonHeapUsage, 0.001)
        
        val gcCount = MetricsRegistry.gcCollectionCounter
            .labels(gcName)
            .get()
        assertEquals(5.0, gcCount, 0.001)
        
        val gcTime = MetricsRegistry.gcCollectionTimeCounter
            .labels(gcName)
            .get()
        assertEquals(0.123, gcTime, 0.001)
    }
    
    @Test
    fun `test histogram metrics`() {
        // Given
        val instrumentId = "BTC-USDT"
        val type = "limit"
        
        // When
        MetricsRegistry.recordOrderProcessingTime(instrumentId, type, 0.0005)
        MetricsRegistry.recordOrderProcessingTime(instrumentId, type, 0.002)
        MetricsRegistry.recordOrderProcessingTime(instrumentId, type, 0.01)
        
        // Then
        val histogram = MetricsRegistry.orderProcessingTimeHistogram
            .labels(instrumentId, type)
        
        // Count should be 3
        assertEquals(3.0, histogram.get().sampleCount, 0.001)
        
        // Sum should be the sum of all observations
        assertEquals(0.0125, histogram.get().sum, 0.0001)
    }
} 