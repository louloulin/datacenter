package com.hftdc

import com.hftdc.api.TradingApi
import com.hftdc.config.AppConfig
import com.hftdc.core.ActorSystemManager
import com.hftdc.engine.OrderBookManager
import com.hftdc.engine.OrderProcessor
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
    val riskManager: com.hftdc.risk.RiskManager
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
        snapshotIntervalMs = config.engine.snapshotInterval.toLong()
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
        riskManager = riskManager
    )
    
    // 创建市场数据发布器
    val marketDataPublisher = com.hftdc.market.WebSocketMarketDataPublisher(
        marketDataProcessor = marketDataProcessor
    )
    
    // 创建交易API
    val tradingApi = TradingApi(actorSystemManager.getRootActor())
    
    return ApplicationComponents(
        actorSystemManager = actorSystemManager,
        orderBookManager = orderBookManager,
        orderProcessor = orderProcessor,
        tradingApi = tradingApi,
        marketDataProcessor = marketDataProcessor,
        marketDataPublisher = marketDataPublisher,
        riskManager = riskManager
    )
}

/**
 * 启动组件
 */
private fun startComponents(components: ApplicationComponents) {
    logger.info { "Starting components..." }
    
    // 启动Actor系统
    components.actorSystemManager.start()
    
    // 其他组件已经在创建时自动启动
    
    logger.info { "All components started" }
}

/**
 * 停止组件
 */
private fun stopComponents(components: ApplicationComponents) {
    logger.info { "Stopping components..." }
    
    // 停止顺序很重要，先停API和市场数据发布器，再停处理器，最后停Actor系统
    
    // 停止市场数据发布器
    components.marketDataPublisher.shutdown()
    
    // 停止市场数据处理器
    components.marketDataProcessor.shutdown()
    
    // 停止订单处理器
    components.orderProcessor.shutdown()
    
    // 停止风险管理器
    components.riskManager.shutdown()
    
    // 停止订单簿管理器
    components.orderBookManager.shutdown()
    
    // 停止Actor系统
    components.actorSystemManager.shutdown()
    
    logger.info { "All components stopped" }
} 