package com.hftdc.metrics

import com.hftdc.engine.OrderBookManager
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * 指标服务配置
 */
data class MetricsConfig(
    val enablePrometheus: Boolean = true,
    val prometheusPort: Int = 9090,
    val collectionIntervalMs: Long = 15000, // 15秒
    val jvmMetricsEnabled: Boolean = true
)

/**
 * 指标服务 - 负责收集和暴露系统指标
 */
class MetricsService(
    private val config: MetricsConfig,
    private val orderBookManager: OrderBookManager
) {
    private val isRunning = AtomicBoolean(false)
    private var metricsServer: HTTPServer? = null
    private var metricsCollectorJob: Job? = null
    private val metricsRegistry = MetricsRegistry
    
    /**
     * 启动指标服务
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            logger.warn { "Metrics service is already running" }
            return
        }
        
        logger.info { "Starting metrics service" }
        
        // 如果启用了JVM指标，注册默认的JVM指标导出器
        if (config.jvmMetricsEnabled) {
            DefaultExports.initialize()
            logger.info { "JVM metrics collection enabled" }
        }
        
        // 如果启用了Prometheus，启动HTTP服务器暴露指标
        if (config.enablePrometheus) {
            try {
                metricsServer = HTTPServer(config.prometheusPort, metricsRegistry.getRegistry())
                logger.info { "Prometheus metrics server started on port ${config.prometheusPort}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to start Prometheus metrics server" }
            }
        }
        
        // 启动指标收集协程
        metricsCollectorJob = CoroutineScope(Dispatchers.Default).launch {
            logger.info { "Metrics collector started with interval ${config.collectionIntervalMs}ms" }
            
            while (isActive) {
                try {
                    collectMetrics()
                } catch (e: Exception) {
                    logger.error(e) { "Error collecting metrics" }
                }
                
                delay(config.collectionIntervalMs)
            }
        }
    }
    
    /**
     * 停止指标服务
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            logger.warn { "Metrics service is not running" }
            return
        }
        
        logger.info { "Stopping metrics service" }
        
        // 停止指标收集协程
        metricsCollectorJob?.cancel()
        metricsCollectorJob = null
        
        // 停止Prometheus HTTP服务器
        metricsServer?.let {
            it.stop()
            metricsServer = null
            logger.info { "Prometheus metrics server stopped" }
        }
    }
    
    /**
     * 收集指标
     */
    private fun collectMetrics() {
        val startTime = Instant.now()
        
        // 收集系统级指标
        collectSystemMetrics()
        
        // 收集订单簿指标
        collectOrderBookMetrics()
        
        // 收集JVM指标
        if (config.jvmMetricsEnabled) {
            collectJvmMetrics()
        }
        
        val duration = Duration.between(startTime, Instant.now()).toMillis()
        logger.debug { "Metrics collection completed in ${duration}ms" }
    }
    
    /**
     * 收集系统级指标
     */
    private fun collectSystemMetrics() {
        try {
            // 更新活跃交易品种数量
            val activeInstruments = orderBookManager.getActiveInstrumentIds()
            metricsRegistry.setActiveInstruments(activeInstruments.size)
            
            // 更新其他系统级指标
            // ...
        } catch (e: Exception) {
            logger.error(e) { "Error collecting system metrics" }
        }
    }
    
    /**
     * 收集订单簿指标
     */
    private fun collectOrderBookMetrics() {
        try {
            val activeInstruments = orderBookManager.getActiveInstrumentIds()
            
            for (instrumentId in activeInstruments) {
                val orderBook = orderBookManager.getOrderBook(instrumentId)
                
                // 获取订单簿快照(深度为5)，用于计算深度指标
                val snapshot = orderBook.getSnapshot(5)
                
                // 更新买单深度
                metricsRegistry.setOrderBookDepth(instrumentId, "buy", snapshot.bids.size)
                
                // 更新卖单深度
                metricsRegistry.setOrderBookDepth(instrumentId, "sell", snapshot.asks.size)
                
                // 计算买单数量并更新指标
                val buyOrderCount = snapshot.bids.sumOf { it.quantity.toInt() }
                metricsRegistry.setOrdersInBook(instrumentId, "buy", buyOrderCount)
                
                // 计算卖单数量并更新指标
                val sellOrderCount = snapshot.asks.sumOf { it.quantity.toInt() }
                metricsRegistry.setOrdersInBook(instrumentId, "sell", sellOrderCount)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error collecting order book metrics" }
        }
    }
    
    /**
     * 收集JVM指标
     */
    private fun collectJvmMetrics() {
        try {
            val memoryMXBean = ManagementFactory.getMemoryMXBean()
            val heapMemoryUsage = memoryMXBean.heapMemoryUsage
            val nonHeapMemoryUsage = memoryMXBean.nonHeapMemoryUsage
            
            // 更新堆内存使用
            metricsRegistry.setJvmMemoryUsage("heap", heapMemoryUsage.used)
            
            // 更新非堆内存使用
            metricsRegistry.setJvmMemoryUsage("non-heap", nonHeapMemoryUsage.used)
            
            // 收集GC指标
            val gcMXBeans = ManagementFactory.getGarbageCollectorMXBeans()
            for (gcMXBean in gcMXBeans) {
                val gcName = gcMXBean.name
                val collectionCount = gcMXBean.collectionCount
                val collectionTime = gcMXBean.collectionTime
                
                if (collectionCount > 0) {
                    metricsRegistry.recordGcCollection(gcName, collectionCount)
                    metricsRegistry.recordGcCollectionTime(gcName, collectionTime / 1000.0)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error collecting JVM metrics" }
        }
    }
} 