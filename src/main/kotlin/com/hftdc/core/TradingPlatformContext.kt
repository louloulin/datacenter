package com.hftdc.core

import com.hftdc.api.TradingApi
import com.hftdc.config.dsl.PlatformConfig
import com.hftdc.engine.OrderBookManager
import com.hftdc.engine.OrderProcessor
import com.hftdc.http.HttpServer
import com.hftdc.journal.JournalService
import com.hftdc.journal.RecoveryService
import com.hftdc.journal.SnapshotManager
import com.hftdc.market.MarketDataProcessor
import com.hftdc.market.WebSocketMarketDataPublisher
import com.hftdc.metrics.MetricsService
import com.hftdc.risk.RiskManager
import mu.KotlinLogging
import kotlin.time.toJavaDuration

/**
 * 交易平台上下文接口
 * 统一管理所有组件的生命周期和依赖
 */
interface TradingPlatformContext {
    /**
     * 平台配置
     */
    val config: PlatformConfig
    
    /**
     * 组件访问方法
     */
    val actorSystemManager: ActorSystemManager
    val orderBookManager: OrderBookManager
    val orderProcessor: OrderProcessor
    val tradingApi: TradingApi
    val marketDataProcessor: MarketDataProcessor
    val marketDataPublisher: WebSocketMarketDataPublisher
    val riskManager: RiskManager
    val journalService: JournalService
    val recoveryService: RecoveryService?
    val snapshotManager: SnapshotManager
    val httpServer: HttpServer
    val metricsService: MetricsService
    
    /**
     * 生命周期方法
     */
    fun start()
    fun stop()
    
    /**
     * 插件管理
     */
    fun registerPlugin(plugin: TradingPlatformPlugin)
    fun getPlugin(name: String): TradingPlatformPlugin?
}

/**
 * 交易平台插件接口
 */
interface TradingPlatformPlugin {
    /**
     * 插件名称
     */
    val name: String
    
    /**
     * 初始化插件
     */
    fun initialize(context: TradingPlatformContext)
    
    /**
     * 启动插件
     */
    fun start()
    
    /**
     * 停止插件
     */
    fun stop()
}

/**
 * 交易平台上下文实现
 */
class TradingPlatformContextImpl(
    override val config: PlatformConfig
) : TradingPlatformContext {
    
    private val logger = KotlinLogging.logger {}
    private val plugins = mutableMapOf<String, TradingPlatformPlugin>()
    
    /**
     * 使用懒加载模式初始化组件，确保正确的依赖顺序
     */
    override val actorSystemManager: ActorSystemManager by lazy { createActorSystemManager() }
    override val orderBookManager: OrderBookManager by lazy { createOrderBookManager() }
    override val journalService: JournalService by lazy { createJournalService() }
    override val snapshotManager: SnapshotManager by lazy { createSnapshotManager() }
    override val recoveryService: RecoveryService? by lazy { createRecoveryService() }
    override val marketDataProcessor: MarketDataProcessor by lazy { createMarketDataProcessor() }
    override val riskManager: RiskManager by lazy { createRiskManager() }
    override val orderProcessor: OrderProcessor by lazy { createOrderProcessor() }
    override val marketDataPublisher: WebSocketMarketDataPublisher by lazy { createMarketDataPublisher() }
    override val tradingApi: TradingApi by lazy { createTradingApi() }
    override val httpServer: HttpServer by lazy { createHttpServer() }
    override val metricsService: MetricsService by lazy { createMetricsService() }
    
    /**
     * 组件创建方法
     */
    private fun createActorSystemManager(): ActorSystemManager {
        logger.info { "创建Actor系统管理器" }
        
        // 这里需要根据新的配置模型创建组件
        // 示例实现
        return ActorSystemManager(
            // 将DSL配置转换为ActorSystemManager需要的参数
            // 在完整实现中需要处理这种转换
            config = com.hftdc.config.AppConfig(
                disruptor = com.hftdc.config.DisruptorConfig(
                    bufferSize = config.disruptor.bufferSize,
                    waitStrategy = config.disruptor.waitStrategy.toString().toLowerCase(),
                    producerType = config.disruptor.producerType.toString()
                ),
                akka = com.hftdc.config.AkkaConfig(
                    clusterEnabled = true,
                    seedNodes = emptyList()
                ),
                db = com.hftdc.config.DbConfig(
                    url = "jdbc:h2:mem:test",
                    username = "sa",
                    password = "",
                    poolSize = 5
                ),
                engine = com.hftdc.config.EngineConfig(
                    maxOrdersPerBook = config.engine.maxOrdersPerBook,
                    maxInstruments = config.engine.maxInstruments,
                    snapshotInterval = config.engine.snapshotInterval.inWholeMilliseconds.toInt(),
                    cleanupIdleActorsAfterMinutes = config.engine.cleanupIdleActorsAfterMinutes
                ),
                recovery = com.hftdc.config.RecoveryConfig(
                    enabled = config.recovery.enabled,
                    includeEventsBeforeSnapshot = config.recovery.includeEventsBeforeSnapshot,
                    eventsBeforeSnapshotTimeWindowMs = config.recovery.eventsBeforeSnapshotTimeWindowMs,
                    autoStartSnapshots = config.recovery.autoStartSnapshots
                ),
                api = com.hftdc.config.ApiConfig(
                    host = config.server.host,
                    port = config.server.port,
                    enableCors = config.server.enableCors,
                    requestTimeoutMs = config.server.requestTimeoutMs
                ),
                monitoring = com.hftdc.config.MonitoringConfig(
                    prometheusEnabled = config.monitoring.prometheusEnabled,
                    prometheusPort = config.monitoring.prometheusPort,
                    metricsIntervalSeconds = config.monitoring.metricsIntervalSeconds
                ),
                performance = null,
                security = null,
                environment = "development"
            )
        )
    }
    
    private fun createOrderBookManager(): OrderBookManager {
        logger.info { "创建订单簿管理器" }
        return OrderBookManager(
            maxInstruments = config.engine.maxInstruments,
            snapshotIntervalMs = config.engine.snapshotInterval.inWholeMilliseconds,
            enableInternalSnapshots = false
        )
    }
    
    private fun createJournalService(): JournalService {
        logger.info { "创建日志服务" }
        return com.hftdc.journal.FileJournalService(
            baseDir = config.journaling.baseDir,
            snapshotDir = config.journaling.snapshotDir,
            flushIntervalMs = config.journaling.flushIntervalMs,
            snapshotIntervalMs = 60000 // 每分钟自动快照一次
        )
    }
    
    private fun createSnapshotManager(): SnapshotManager {
        logger.info { "创建快照管理器" }
        return SnapshotManager(
            journalService = journalService,
            orderBookManager = orderBookManager,
            snapshotIntervalMs = config.engine.snapshotInterval.inWholeMilliseconds,
            initialDelayMs = 10000, // 启动10秒后开始快照
            snapshotDepth = 20 // 20层深度
        )
    }
    
    private fun createRecoveryService(): RecoveryService? {
        return if (config.recovery.enabled) {
            logger.info { "创建恢复服务" }
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
    }
    
    private fun createMarketDataProcessor(): MarketDataProcessor {
        logger.info { "创建市场数据处理器" }
        
        // 获取内部数据源配置
        val internalSource = config.marketData.getSources()["internal"]
        val aggregationLevel = internalSource?.aggregationLevel?.inWholeMilliseconds ?: 1000L
        
        return MarketDataProcessor(
            orderBookManager = orderBookManager,
            snapshotIntervalMs = aggregationLevel
        )
    }
    
    private fun createRiskManager(): RiskManager {
        logger.info { "创建风险管理器" }
        return RiskManager()
    }
    
    private fun createOrderProcessor(): OrderProcessor {
        logger.info { "创建订单处理器" }
        
        // 创建指标处理器
        val orderMetricsHandler = com.hftdc.metrics.OrderMetricsHandler()
        
        return OrderProcessor(
            bufferSize = config.disruptor.bufferSize,
            orderBookManager = orderBookManager,
            marketDataProcessor = marketDataProcessor,
            riskManager = riskManager,
            journalService = journalService,
            orderMetricsHandler = orderMetricsHandler
        )
    }
    
    private fun createMarketDataPublisher(): WebSocketMarketDataPublisher {
        logger.info { "创建市场数据发布器" }
        
        val wsPublisher = config.marketData.getPublishers()["websocket"]
        
        return WebSocketMarketDataPublisher(
            marketDataProcessor = marketDataProcessor
            // 在完整实现中需要传入WebSocket配置参数
        )
    }
    
    private fun createTradingApi(): TradingApi {
        logger.info { "创建交易API" }
        return TradingApi(
            rootActor = actorSystemManager.getRootActor(),
            orderBookManager = orderBookManager
        )
    }
    
    private fun createHttpServer(): HttpServer {
        logger.info { "创建HTTP服务器" }
        return HttpServer(
            config = com.hftdc.http.HttpServerConfig(
                host = config.server.host,
                port = config.server.port,
                enableCors = config.server.enableCors,
                requestTimeoutMs = config.server.requestTimeoutMs
            ),
            tradingApi = tradingApi,
            orderBookManager = orderBookManager,
            journalService = journalService,
            recoveryService = recoveryService
        )
    }
    
    private fun createMetricsService(): MetricsService {
        logger.info { "创建指标服务" }
        return com.hftdc.metrics.MetricsService(
            config = com.hftdc.metrics.MetricsConfig(
                enablePrometheus = config.monitoring.prometheusEnabled,
                prometheusPort = config.monitoring.prometheusPort,
                collectionIntervalMs = config.monitoring.metricsIntervalSeconds * 1000L,
                jvmMetricsEnabled = config.monitoring.jvmMetricsEnabled
            ),
            orderBookManager = orderBookManager
        )
    }
    
    /**
     * 启动平台
     */
    override fun start() {
        logger.info { "启动交易平台..." }
        
        try {
            // 如果启用了恢复功能，首先执行恢复
            recoveryService?.let {
                logger.info { "执行恢复过程..." }
                it.recover()
                logger.info { "恢复完成" }
            }
            
            // 启动Actor系统
            actorSystemManager.start()
            
            // 启动快照管理器
            snapshotManager.start()
            
            // 启动指标服务
            metricsService.start()
            
            // 启动HTTP服务器
            httpServer.start()
            
            // 启动所有插件
            plugins.values.forEach { it.start() }
            
            logger.info { "交易平台启动完成" }
        } catch (e: Exception) {
            logger.error(e) { "启动交易平台失败" }
            stop()
            throw e
        }
    }
    
    /**
     * 停止平台
     */
    override fun stop() {
        logger.info { "停止交易平台..." }
        
        try {
            // 停止所有插件
            plugins.values.forEach { 
                try {
                    it.stop()
                } catch (e: Exception) {
                    logger.error(e) { "停止插件 ${it.name} 失败" }
                }
            }
            
            // 停止HTTP服务器
            httpServer.stop()
            
            // 停止指标服务
            metricsService.stop()
            
            // 停止市场数据发布器
            marketDataPublisher.shutdown()
            
            // 停止市场数据处理器
            marketDataProcessor.shutdown()
            
            // 停止订单处理器
            orderProcessor.shutdown()
            
            // 停止风险管理器
            riskManager.shutdown()
            
            // 停止快照管理器
            snapshotManager.stop()
            
            // 停止订单簿管理器
            orderBookManager.shutdown()
            
            // 停止日志服务
            journalService.shutdown()
            
            // 停止Actor系统
            actorSystemManager.shutdown()
            
            logger.info { "交易平台已停止" }
        } catch (e: Exception) {
            logger.error(e) { "停止交易平台过程中发生错误" }
            throw e
        }
    }
    
    /**
     * 注册插件
     */
    override fun registerPlugin(plugin: TradingPlatformPlugin) {
        logger.info { "注册插件: ${plugin.name}" }
        plugins[plugin.name] = plugin
        plugin.initialize(this)
    }
    
    /**
     * 获取插件
     */
    override fun getPlugin(name: String): TradingPlatformPlugin? {
        return plugins[name]
    }
} 