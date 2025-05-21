package com.hftdc.config.dsl

import com.lmax.disruptor.dsl.ProducerType
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * 交易平台DSL标记注解
 * 用于限制DSL作用域和提供更好的IDE支持
 */
@DslMarker
annotation class TradingPlatformDsl

/**
 * 平台配置类 - 保存通过DSL构建的完整配置
 */
class PlatformConfig {
    var server: ServerConfig = ServerConfig()
    var disruptor: DisruptorConfig = DisruptorConfig()
    var engine: EngineConfig = EngineConfig()
    var monitoring: MonitoringConfig = MonitoringConfig()
    var security: SecurityConfig? = null
    var recovery: RecoveryConfig = RecoveryConfig()
    var journaling: JournalingConfig = JournalingConfig()
    var marketData: MarketDataConfig = MarketDataConfig()
}

/**
 * 服务器配置
 */
@TradingPlatformDsl
class ServerConfig {
    var host: String = "0.0.0.0"
    var port: Int = 8080
    var enableSsl: Boolean = false
    var requestTimeoutMs: Long = 30000
    var enableCors: Boolean = true
}

/**
 * Disruptor配置
 */
@TradingPlatformDsl
class DisruptorConfig {
    var bufferSize: Int = 1024
    var waitStrategy: WaitStrategy = WaitStrategy.BLOCKING
    var producerType: ProducerType = ProducerType.SINGLE
}

/**
 * 等待策略枚举
 */
enum class WaitStrategy {
    BLOCKING,
    BUSY_SPIN,
    YIELDING,
    SLEEPING
}

/**
 * 引擎配置
 */
@TradingPlatformDsl
class EngineConfig {
    var maxOrdersPerBook: Int = 100000
    var maxInstruments: Int = 100
    var snapshotInterval: Duration = 60.toDuration(DurationUnit.SECONDS)
    var cleanupIdleActorsAfterMinutes: Int = 10
    
    private val orderBooks = mutableMapOf<String, OrderBookConfig>()
    
    fun orderBook(instrumentId: String, init: OrderBookConfig.() -> Unit) {
        val config = OrderBookConfig(instrumentId).apply(init)
        orderBooks[instrumentId] = config
    }
    
    fun getOrderBooks(): Map<String, OrderBookConfig> = orderBooks.toMap()
}

/**
 * 单个订单簿配置
 */
@TradingPlatformDsl
class OrderBookConfig(val instrumentId: String) {
    var pricePrecision: Int = 2
    var quantityPrecision: Int = 8
    var priceTickSize: Double = 0.01
    var quantityMinimum: Double = 0.0001
    var initialSnapshot: Boolean = false
}

/**
 * 监控配置
 */
@TradingPlatformDsl
class MonitoringConfig {
    var prometheusEnabled: Boolean = false
    var prometheusPort: Int = 9090
    var metricsIntervalSeconds: Int = 5
    var jvmMetricsEnabled: Boolean = true
    
    fun prometheus(init: PrometheusConfig.() -> Unit) {
        val config = PrometheusConfig().apply(init)
        prometheusEnabled = config.enabled
        prometheusPort = config.port
    }
    
    fun metrics(init: MetricsConfig.() -> Unit) {
        val config = MetricsConfig().apply(init)
        metricsIntervalSeconds = config.collectInterval.inWholeSeconds.toInt()
        jvmMetricsEnabled = config.jvmMetricsEnabled
    }
}

/**
 * Prometheus配置
 */
@TradingPlatformDsl
class PrometheusConfig {
    var enabled: Boolean = false
    var port: Int = 9090
}

/**
 * 指标配置
 */
@TradingPlatformDsl
class MetricsConfig {
    var collectInterval: Duration = 5.toDuration(DurationUnit.SECONDS)
    var jvmMetricsEnabled: Boolean = true
}

/**
 * 安全配置
 */
@TradingPlatformDsl
class SecurityConfig {
    var sslEnabled: Boolean = false
    var sslCertificate: String = ""
    var sslPrivateKey: String = ""
    var apiKeyAuthEnabled: Boolean = false
    var ipWhitelistEnabled: Boolean = false
    var allowedIps: List<String> = emptyList()
    
    fun ssl(init: SslConfig.() -> Unit) {
        val config = SslConfig().apply(init)
        sslEnabled = true
        sslCertificate = config.certificatePath
        sslPrivateKey = config.privateKeyPath
    }
}

/**
 * SSL配置
 */
@TradingPlatformDsl
class SslConfig {
    var certificatePath: String = ""
    var privateKeyPath: String = ""
}

/**
 * 恢复配置
 */
@TradingPlatformDsl
class RecoveryConfig {
    var enabled: Boolean = false
    var includeEventsBeforeSnapshot: Boolean = false
    var eventsBeforeSnapshotTimeWindowMs: Long = 5000
    var autoStartSnapshots: Boolean = false
}

/**
 * 日志配置
 */
@TradingPlatformDsl
class JournalingConfig {
    var baseDir: String = "data/journal"
    var snapshotDir: String = "data/snapshots"
    var flushIntervalMs: Long = 1000
}

/**
 * 市场数据配置
 */
@TradingPlatformDsl
class MarketDataConfig {
    private val sources = mutableMapOf<String, DataSourceConfig>()
    private val publishers = mutableMapOf<String, PublisherConfig>()
    
    fun source(name: String, init: DataSourceConfig.() -> Unit) {
        val config = DataSourceConfig().apply(init)
        sources[name] = config
    }
    
    fun publisher(name: String, init: PublisherConfig.() -> Unit) {
        val config = PublisherConfig().apply(init)
        publishers[name] = config
    }
    
    fun getSources(): Map<String, DataSourceConfig> = sources.toMap()
    fun getPublishers(): Map<String, PublisherConfig> = publishers.toMap()
}

/**
 * 数据源配置
 */
@TradingPlatformDsl
class DataSourceConfig {
    var instruments: List<String> = emptyList()
    var aggregationLevel: Duration = 1.toDuration(DurationUnit.SECONDS)
    var endpoint: String = ""
    var reconnectInterval: Duration = 5.toDuration(DurationUnit.SECONDS)
}

/**
 * 发布器配置
 */
@TradingPlatformDsl
class PublisherConfig {
    var port: Int = 8081
    var path: String = "/market"
    var throttleInterval: Duration = 100.toDuration(DurationUnit.MILLISECONDS)
}

/**
 * 交易平台DSL顶级函数
 */
fun tradingPlatform(init: PlatformConfig.() -> Unit): PlatformConfig {
    return PlatformConfig().apply(init)
}

/**
 * 服务器配置DSL函数
 */
fun PlatformConfig.server(init: ServerConfig.() -> Unit) {
    server = ServerConfig().apply(init)
}

/**
 * Disruptor配置DSL函数
 */
fun PlatformConfig.disruptor(init: DisruptorConfig.() -> Unit) {
    disruptor = DisruptorConfig().apply(init)
}

/**
 * 引擎配置DSL函数
 */
fun PlatformConfig.engine(init: EngineConfig.() -> Unit) {
    engine = EngineConfig().apply(init)
}

/**
 * 监控配置DSL函数
 */
fun PlatformConfig.monitoring(init: MonitoringConfig.() -> Unit) {
    monitoring = MonitoringConfig().apply(init)
}

/**
 * 安全配置DSL函数
 */
fun PlatformConfig.security(init: SecurityConfig.() -> Unit) {
    security = SecurityConfig().apply(init)
}

/**
 * 恢复配置DSL函数
 */
fun PlatformConfig.recovery(init: RecoveryConfig.() -> Unit) {
    recovery = RecoveryConfig().apply(init)
}

/**
 * 日志配置DSL函数
 */
fun PlatformConfig.journaling(init: JournalingConfig.() -> Unit) {
    journaling = JournalingConfig().apply(init)
}

/**
 * 市场数据配置DSL函数
 */
fun PlatformConfig.marketData(init: MarketDataConfig.() -> Unit) {
    marketData = MarketDataConfig().apply(init)
} 