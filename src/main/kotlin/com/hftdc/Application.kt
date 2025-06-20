package com.hftdc

import com.hftdc.api.TradingApi
import com.hftdc.config.AppConfig
import com.hftdc.core.ActorSystemManager
import com.hftdc.engine.OrderBookManager
import com.hftdc.engine.OrderProcessor
import com.hftdc.http.HttpServer
import com.hftdc.http.HttpServerConfig
import com.hftdc.journal.FileJournalService
import com.hftdc.journal.JournalService
import com.hftdc.journal.RecoveryService
import com.hftdc.journal.SnapshotManager
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "Starting High-Frequency Trading Data Center" }
    
    try {
        // 加载配置
        val config = AppConfig.load()
        logger.info { "Loaded configuration" }
        
        // 创建组件
        val components = createComponents(config)
        
        // 启动组件
        startComponents(components)
        
        // 添加JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info { "Shutting down..." }
            stopComponents(components)
        })
        
        logger.info { "High-Frequency Trading Data Center started successfully" }
    } catch (e: Exception) {
        logger.error(e) { "Failed to start the application" }
        System.exit(1)
    }
}

/**
 * 应用程序组件
 */
data class ApplicationComponents(
    val actorSystemManager: ActorSystemManager,
    val orderBookManager: OrderBookManager,
    val orderProcessor: OrderProcessor,
    val tradingApi: TradingApi,
    val marketDataProcessor: com.hftdc.market.MarketDataProcessor,
    val marketDataPublisher: com.hftdc.market.WebSocketMarketDataPublisher,
    val riskManager: com.hftdc.risk.RiskManager,
    val journalService: JournalService,
    val recoveryService: RecoveryService?,
    val snapshotManager: SnapshotManager,
    val httpServer: HttpServer,
    val metricsService: com.hftdc.metrics.MetricsService,
    val orderMetricsHandler: com.hftdc.metrics.OrderMetricsHandler
)

/**
 * 创建组件
 */
private fun createComponents(config: AppConfig): ApplicationComponents {
    logger.info { "Creating components..." }
    
    // 创建Actor系统管理器
    val actorSystemManager = ActorSystemManager(config)
    
    // 创建订单簿管理器
    val orderBookManager = OrderBookManager(
        maxInstruments = config.engine.maxInstruments,
        snapshotIntervalMs = config.engine.snapshotInterval.toLong(),
        enableInternalSnapshots = false // 使用SnapshotManager代替内部快照机制
    )
    
    // 创建日志服务
    val journalService = FileJournalService(
        baseDir = "data/journal",
        snapshotDir = "data/snapshots",
        flushIntervalMs = 1000, // 每秒刷新一次
        snapshotIntervalMs = 60000 // 每分钟自动快照一次
    )
    
    // 创建快照管理器
    val snapshotManager = SnapshotManager(
        journalService = journalService,
        orderBookManager = orderBookManager,
        snapshotIntervalMs = config.engine.snapshotInterval.toLong(),
        initialDelayMs = 10000, // 启动10秒后开始快照
        snapshotDepth = 20 // 20层深度
    )
    
    // 创建恢复服务
    val recoveryService = if (config.recovery.enabled) {
        RecoveryService(
            journalService = journalService,
            orderBookManager = orderBookManager,
            config = com.hftdc.journal.RecoveryConfig(
                includeEventsBeforeSnapshot = config.recovery.includeEventsBeforeSnapshot,
                eventsBeforeSnapshotTimeWindowMs = config.recovery.eventsBeforeSnapshotTimeWindowMs
            )
        )
    } else {
        null
    }
    
    // 创建指标处理器
    val orderMetricsHandler = com.hftdc.metrics.OrderMetricsHandler()
    
    // 创建指标服务
    val metricsService = com.hftdc.metrics.MetricsService(
        config = com.hftdc.metrics.MetricsConfig(
            enablePrometheus = config.monitoring.prometheusEnabled,
            prometheusPort = config.monitoring.prometheusPort,
            collectionIntervalMs = config.monitoring.metricsIntervalSeconds * 1000L,
            jvmMetricsEnabled = true
        ),
        orderBookManager = orderBookManager
    )
    
    // 创建风险管理器
    val riskManager = com.hftdc.risk.RiskManager()
    
    // 创建市场数据处理器
    val marketDataProcessor = com.hftdc.market.MarketDataProcessor(
        orderBookManager = orderBookManager,
        snapshotIntervalMs = 1000 // 每秒发布一次市场数据
    )
    
    // 创建订单处理器
    val orderProcessor = OrderProcessor(
        bufferSize = config.disruptor.bufferSize,
        orderBookManager = orderBookManager,
        marketDataProcessor = marketDataProcessor,
        riskManager = riskManager,
        journalService = journalService,
        orderMetricsHandler = orderMetricsHandler // 添加指标处理器
    )
    
    // 创建市场数据发布器
    val marketDataPublisher = com.hftdc.market.WebSocketMarketDataPublisher(
        marketDataProcessor = marketDataProcessor
    )
    
    // 创建交易API
    val tradingApi = TradingApi(
        rootActor = actorSystemManager.getRootActor(),
        orderBookManager = orderBookManager
    )
    
    // 创建HTTP服务器
    val httpServer = HttpServer(
        config = HttpServerConfig(
            host = config.api.host,
            port = config.api.port,
            enableCors = true,
            requestTimeoutMs = 30000
        ),
        tradingApi = tradingApi,
        orderBookManager = orderBookManager,
        journalService = journalService,
        recoveryService = recoveryService
    )
    
    return ApplicationComponents(
        actorSystemManager = actorSystemManager,
        orderBookManager = orderBookManager,
        orderProcessor = orderProcessor,
        tradingApi = tradingApi,
        marketDataProcessor = marketDataProcessor,
        marketDataPublisher = marketDataPublisher,
        riskManager = riskManager,
        journalService = journalService,
        recoveryService = recoveryService,
        snapshotManager = snapshotManager,
        httpServer = httpServer,
        metricsService = metricsService,
        orderMetricsHandler = orderMetricsHandler
    )
}

/**
 * 启动组件
 */
private fun startComponents(components: ApplicationComponents) {
    logger.info { "Starting components..." }
    
    // 如果启用了恢复功能，首先执行恢复
    components.recoveryService?.let {
        logger.info { "Executing recovery process..." }
        it.recover()
        logger.info { "Recovery completed" }
    }
    
    // 启动Actor系统
    components.actorSystemManager.start()
    
    // 启动快照管理器
    components.snapshotManager.start()
    
    // 启动指标服务
    components.metricsService.start()
    
    // 启动HTTP服务器
    components.httpServer.start()
    
    // 其他组件已经在创建时自动启动
    
    logger.info { "All components started" }
}

/**
 * 停止组件
 */
private fun stopComponents(components: ApplicationComponents) {
    logger.info { "Stopping components..." }
    
    // 停止顺序很重要，先停API服务器，再停处理器，最后停核心组件
    
    // 停止HTTP服务器
    components.httpServer.stop()
    
    // 停止指标服务
    components.metricsService.stop()
    
    // 停止市场数据发布器
    components.marketDataPublisher.shutdown()
    
    // 停止市场数据处理器
    components.marketDataProcessor.shutdown()
    
    // 停止订单处理器
    components.orderProcessor.shutdown()
    
    // 停止风险管理器
    components.riskManager.shutdown()
    
    // 停止快照管理器
    components.snapshotManager.stop()
    
    // 停止订单簿管理器
    components.orderBookManager.shutdown()
    
    // 停止日志服务
    components.journalService.shutdown()
    
    // 停止Actor系统
    components.actorSystemManager.shutdown()
    
    logger.info { "All components stopped" }
} 