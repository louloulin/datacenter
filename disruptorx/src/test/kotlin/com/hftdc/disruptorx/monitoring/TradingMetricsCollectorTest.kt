package com.hftdc.disruptorx.monitoring

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.math.BigDecimal
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

class TradingMetricsCollectorTest {
    
    private lateinit var metricsCollector: TradingMetricsCollector
    private lateinit var testAlertManager: TestAlertManager
    
    @BeforeEach
    fun setUp() {
        testAlertManager = TestAlertManager()
        metricsCollector = TradingMetricsCollector(
            slaThresholdNanos = 50_000, // 50μs
            alertManager = testAlertManager
        )
    }
    
    @Test
    fun `test latency recording and statistics`() = runBlocking {
        val operation = "trade-execution"
        
        // 记录一些延迟数据
        metricsCollector.recordLatency(operation, 10.microseconds)
        metricsCollector.recordLatency(operation, 20.microseconds)
        metricsCollector.recordLatency(operation, 30.microseconds)
        metricsCollector.recordLatency(operation, 40.microseconds)
        metricsCollector.recordLatency(operation, 100.microseconds) // 这个会触发告警
        
        val stats = metricsCollector.getLatencyStats(operation)
        assertNotNull(stats)
        
        assertEquals(5, stats!!.count)
        assertTrue(stats.p50.inWholeMicroseconds >= 20)
        assertTrue(stats.p99.inWholeMicroseconds >= 90)
        assertTrue(stats.max.inWholeMicroseconds >= 100)
        
        // 检查是否触发了SLA告警
        assertTrue(testAlertManager.alerts.isNotEmpty())
        val alert = testAlertManager.alerts.first()
        assertEquals(AlertType.LATENCY_BREACH, alert.type)
    }
    
    @Test
    fun `test throughput recording and statistics`() = runBlocking {
        val operation = "order-processing"
        
        // 记录吞吐量数据
        repeat(1000) {
            metricsCollector.recordThroughput(operation, 1)
        }
        
        // 等待一小段时间让窗口统计生效
        delay(100)
        
        val stats = metricsCollector.getThroughputStats(operation)
        assertNotNull(stats)
        
        assertEquals(1000, stats!!.totalCount)
        assertTrue(stats.ratePerSecond > 0)
        assertTrue(stats.ratePerMinute > 0)
    }
    
    @Test
    fun `test trade volume recording`() {
        val symbol = "AAPL"
        val volume1 = BigDecimal("100.50")
        val volume2 = BigDecimal("200.25")
        
        metricsCollector.recordTradeVolume(symbol, volume1)
        metricsCollector.recordTradeVolume(symbol, volume2)
        
        val volumeStats = metricsCollector.getTradeVolumeStats()
        assertTrue(volumeStats.containsKey(symbol))
        
        val totalVolume = volumeStats[symbol]!!
        assertEquals(volume1.add(volume2), totalVolume)
    }
    
    @Test
    fun `test pnl recording`() {
        val strategy = "momentum-strategy"
        val pnl1 = BigDecimal("1500.75")
        val pnl2 = BigDecimal("-500.25")
        
        metricsCollector.recordPnL(strategy, pnl1)
        metricsCollector.recordPnL(strategy, pnl2)
        
        val pnlStats = metricsCollector.getPnLStats()
        assertTrue(pnlStats.containsKey(strategy))
        
        val totalPnL = pnlStats[strategy]!!
        val expectedTotal = pnl1.add(pnl2)
        assertEquals(0, expectedTotal.compareTo(totalPnL),
            "Expected PnL $expectedTotal but got $totalPnL")
    }
    
    @Test
    fun `test error recording`() {
        val operation = "risk-check"
        
        metricsCollector.recordError(operation, 5)
        metricsCollector.recordError(operation, 3)
        
        // 错误计数应该累加
        // 注意：这里我们需要通过其他方式验证错误计数，因为当前API没有直接获取错误统计的方法
        // 在实际实现中，应该添加getErrorStats方法
    }
    
    @Test
    fun `test multiple operations tracking`() = runBlocking {
        val operations = listOf("trade", "quote", "risk-check", "settlement")
        
        operations.forEach { operation ->
            metricsCollector.recordLatency(operation, 25.microseconds)
            metricsCollector.recordThroughput(operation, 10)
        }
        
        val allLatencyStats = metricsCollector.getAllLatencyStats()
        val allThroughputStats = metricsCollector.getAllThroughputStats()
        
        assertEquals(operations.size, allLatencyStats.size)
        assertEquals(operations.size, allThroughputStats.size)
        
        operations.forEach { operation ->
            assertTrue(allLatencyStats.containsKey(operation))
            assertTrue(allThroughputStats.containsKey(operation))
        }
    }
    
    @Test
    fun `test sla threshold alerting`() {
        val operation = "critical-trade"
        
        // 记录正常延迟（不应触发告警）
        metricsCollector.recordLatency(operation, 30.microseconds)
        assertEquals(0, testAlertManager.alerts.size)
        
        // 记录超过SLA的延迟（应触发告警）
        metricsCollector.recordLatency(operation, 80.microseconds)
        assertEquals(1, testAlertManager.alerts.size)
        
        val alert = testAlertManager.alerts.first()
        assertEquals(AlertType.LATENCY_BREACH, alert.type)
        assertEquals(AlertSeverity.WARNING, alert.severity)
        
        // 记录严重超过SLA的延迟（应触发严重告警）
        metricsCollector.recordLatency(operation, 150.microseconds)
        assertEquals(2, testAlertManager.alerts.size)
        
        val criticalAlert = testAlertManager.alerts.last()
        assertEquals(AlertSeverity.CRITICAL, criticalAlert.severity)
    }
    
    @Test
    fun `test metrics reset`() = runBlocking {
        val operation = "test-operation"
        
        // 记录一些数据
        metricsCollector.recordLatency(operation, 50.microseconds)
        metricsCollector.recordThroughput(operation, 100)
        
        var stats = metricsCollector.getLatencyStats(operation)
        assertNotNull(stats)
        assertTrue(stats!!.count > 0)
        
        // 重置指标
        metricsCollector.resetMetrics(operation)
        
        // 重置后应该没有数据
        stats = metricsCollector.getLatencyStats(operation)
        if (stats != null) {
            assertEquals(0, stats.count)
        }
    }
    
    @Test
    fun `test high frequency recording performance`() {
        val operation = "high-freq-trade"
        val iterations = 100_000
        
        val startTime = System.nanoTime()
        
        repeat(iterations) {
            metricsCollector.recordTradeLatency(operation, 25_000) // 25μs
        }
        
        val endTime = System.nanoTime()
        val totalTime = endTime - startTime
        val avgTimePerRecord = totalTime / iterations
        
        println("Average time per record: ${avgTimePerRecord}ns")
        
        // 每次记录应该非常快（小于1μs）
        assertTrue(avgTimePerRecord < 1000, "Recording should be very fast: ${avgTimePerRecord}ns")
    }
    
    @Test
    fun `test concurrent recording`() = runBlocking {
        val operation = "concurrent-test"
        val threadsCount = 10
        val recordsPerThread = 1000
        
        val jobs = (1..threadsCount).map { threadId ->
            async {
                repeat(recordsPerThread) {
                    metricsCollector.recordLatency(operation, (20 + threadId).microseconds)
                    metricsCollector.recordThroughput(operation, 1)
                }
            }
        }

        // 等待所有任务完成
        jobs.forEach { it.await() }
        
        val latencyStats = metricsCollector.getLatencyStats(operation)
        val throughputStats = metricsCollector.getThroughputStats(operation)
        
        assertNotNull(latencyStats)
        assertNotNull(throughputStats)
        
        assertEquals(threadsCount * recordsPerThread.toLong(), latencyStats!!.count)
        assertEquals(threadsCount * recordsPerThread.toLong(), throughputStats!!.totalCount)
    }
    
    @Test
    fun `test window statistics rotation`() = runBlocking {
        val operation = "window-test"

        // 记录一些数据
        repeat(100) {
            metricsCollector.recordThroughput(operation, 1)
        }

        val initialStats = metricsCollector.getThroughputStats(operation)
        assertNotNull(initialStats)
        assertTrue(initialStats!!.ratePerSecond >= 0, "Rate should be non-negative")

        // 等待一小段时间让统计生效
        delay(50)

        // 记录更多数据
        repeat(50) {
            metricsCollector.recordThroughput(operation, 1)
        }

        val newStats = metricsCollector.getThroughputStats(operation)
        assertNotNull(newStats)

        // 总计数应该包含所有记录
        assertEquals(150, newStats!!.totalCount,
            "Total count should be exactly 150, but was ${newStats.totalCount}")

        // 验证速率计算
        assertTrue(newStats.ratePerSecond >= 0, "Rate should be non-negative")
    }
}

/**
 * 测试用的告警管理器
 */
class TestAlertManager : AlertManager {
    val alerts = mutableListOf<Alert>()
    
    override fun sendAlert(alert: Alert) {
        alerts.add(alert)
        println("Test Alert: ${alert.type} - ${alert.message}")
    }
    
    fun clearAlerts() {
        alerts.clear()
    }
    
    fun getAlertsOfType(type: AlertType): List<Alert> {
        return alerts.filter { it.type == type }
    }
}
