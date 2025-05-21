package com.hftdc.metrics

import io.prometheus.client.*
import java.util.concurrent.ConcurrentHashMap
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 全局度量注册表 - 单例
 */
object MetricsRegistry {
    // 度量注册表
    private val registry = CollectorRegistry.defaultRegistry
    
    // 已注册的度量指标
    private val metrics = ConcurrentHashMap<String, Any>()
    
    // 订单处理计数器
    val orderProcessedCounter: Counter = Counter.build()
        .name("hftdc_orders_processed_total")
        .help("Total number of orders processed")
        .labelNames("instrument", "side", "type")
        .register(registry)
        .also { metrics["orderProcessedCounter"] = it }
    
    // 订单执行计数器
    val orderExecutedCounter: Counter = Counter.build()
        .name("hftdc_orders_executed_total")
        .help("Total number of orders executed (partial or full)")
        .labelNames("instrument", "side")
        .register(registry)
        .also { metrics["orderExecutedCounter"] = it }
    
    // 成交数量计数器
    val tradeVolumeCounter: Counter = Counter.build()
        .name("hftdc_trade_volume_total")
        .help("Total volume of trades executed")
        .labelNames("instrument")
        .register(registry)
        .also { metrics["tradeVolumeCounter"] = it }
    
    // 订单取消计数器
    val ordersCancelledCounter: Counter = Counter.build()
        .name("hftdc_orders_cancelled_total")
        .help("Total number of orders cancelled")
        .labelNames("instrument", "side")
        .register(registry)
        .also { metrics["ordersCancelledCounter"] = it }
    
    // 订单处理延迟（直方图）
    val orderProcessingTimeHistogram: Histogram = Histogram.build()
        .name("hftdc_order_processing_time_seconds")
        .help("Order processing time in seconds")
        .labelNames("instrument", "type")
        .buckets(0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1, 0.5)
        .register(registry)
        .also { metrics["orderProcessingTimeHistogram"] = it }
    
    // 订单簿深度
    val orderBookDepthGauge: Gauge = Gauge.build()
        .name("hftdc_order_book_depth")
        .help("Current depth of the order book")
        .labelNames("instrument", "side")
        .register(registry)
        .also { metrics["orderBookDepthGauge"] = it }
    
    // 订单簿中订单数量
    val ordersInBookGauge: Gauge = Gauge.build()
        .name("hftdc_orders_in_book")
        .help("Current number of orders in the book")
        .labelNames("instrument", "side")
        .register(registry)
        .also { metrics["ordersInBookGauge"] = it }
    
    // 活跃交易品种数量
    val activeInstrumentsGauge: Gauge = Gauge.build()
        .name("hftdc_active_instruments")
        .help("Current number of active instruments")
        .register(registry)
        .also { metrics["activeInstrumentsGauge"] = it }
    
    // JVM内存使用
    val jvmMemoryUsageGauge: Gauge = Gauge.build()
        .name("hftdc_jvm_memory_usage_bytes")
        .help("JVM memory usage in bytes")
        .labelNames("type") // heap, non-heap
        .register(registry)
        .also { metrics["jvmMemoryUsageGauge"] = it }
    
    // GC次数和时间
    val gcCollectionCounter: Counter = Counter.build()
        .name("hftdc_gc_collections_total")
        .help("Total number of garbage collections")
        .labelNames("gc")
        .register(registry)
        .also { metrics["gcCollectionCounter"] = it }
    
    val gcCollectionTimeCounter: Counter = Counter.build()
        .name("hftdc_gc_collection_time_seconds_total")
        .help("Total time spent in garbage collections")
        .labelNames("gc")
        .register(registry)
        .also { metrics["gcCollectionTimeCounter"] = it }
    
    /**
     * 获取注册表
     */
    fun getRegistry(): CollectorRegistry = registry
    
    /**
     * 记录订单处理
     */
    fun recordOrderProcessed(instrumentId: String, side: String, type: String) {
        orderProcessedCounter.labels(instrumentId, side, type).inc()
    }
    
    /**
     * 记录订单执行
     */
    fun recordOrderExecuted(instrumentId: String, side: String) {
        orderExecutedCounter.labels(instrumentId, side).inc()
    }
    
    /**
     * 记录成交量
     */
    fun recordTradeVolume(instrumentId: String, volume: Long) {
        tradeVolumeCounter.labels(instrumentId).inc(volume.toDouble())
    }
    
    /**
     * 记录订单取消
     */
    fun recordOrderCancelled(instrumentId: String, side: String) {
        ordersCancelledCounter.labels(instrumentId, side).inc()
    }
    
    /**
     * 记录订单处理时间
     */
    fun recordOrderProcessingTime(instrumentId: String, type: String, timeInSeconds: Double) {
        orderProcessingTimeHistogram.labels(instrumentId, type).observe(timeInSeconds)
    }
    
    /**
     * 设置订单簿深度
     */
    fun setOrderBookDepth(instrumentId: String, side: String, depth: Int) {
        orderBookDepthGauge.labels(instrumentId, side).set(depth.toDouble())
    }
    
    /**
     * 设置订单簿中的订单数量
     */
    fun setOrdersInBook(instrumentId: String, side: String, count: Int) {
        ordersInBookGauge.labels(instrumentId, side).set(count.toDouble())
    }
    
    /**
     * 设置活跃交易品种数量
     */
    fun setActiveInstruments(count: Int) {
        activeInstrumentsGauge.set(count.toDouble())
    }
    
    /**
     * 设置JVM内存使用
     */
    fun setJvmMemoryUsage(type: String, bytes: Long) {
        jvmMemoryUsageGauge.labels(type).set(bytes.toDouble())
    }
    
    /**
     * 记录GC收集
     */
    fun recordGcCollection(gcName: String, count: Long) {
        gcCollectionCounter.labels(gcName).inc(count.toDouble())
    }
    
    /**
     * 记录GC收集时间
     */
    fun recordGcCollectionTime(gcName: String, timeInSeconds: Double) {
        gcCollectionTimeCounter.labels(gcName).inc(timeInSeconds)
    }
    
    init {
        logger.info { "Metrics registry initialized with ${metrics.size} metrics" }
    }
} 