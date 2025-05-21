package com.hftdc.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File

/**
 * 应用程序配置类
 */
data class AppConfig(
    val disruptor: DisruptorConfig,
    val akka: AkkaConfig,
    val db: DbConfig,
    val engine: EngineConfig,
    val recovery: RecoveryConfig,
    val api: ApiConfig,
    val monitoring: MonitoringConfig,
    val performance: PerformanceConfig? = null,
    val security: SecurityConfig? = null,
    val environment: String = "development"
) {
    companion object {
        /**
         * 从配置文件加载配置
         */
        fun load(): AppConfig {
            val environment = System.getProperty("env", "development")
            val config = loadConfig(environment)
            return AppConfig(
                disruptor = DisruptorConfig.fromConfig(config.getConfig("disruptor")),
                akka = AkkaConfig.fromConfig(config.getConfig("akka")),
                db = DbConfig.fromConfig(config.getConfig("db")),
                engine = EngineConfig.fromConfig(config.getConfig("engine")),
                recovery = RecoveryConfig.fromConfig(config.getConfig("recovery")),
                api = ApiConfig.fromConfig(config.getConfig("api")),
                monitoring = MonitoringConfig.fromConfig(config.getConfig("monitoring")),
                performance = if (config.hasPath("performance")) PerformanceConfig.fromConfig(config.getConfig("performance")) else null,
                security = if (config.hasPath("security")) SecurityConfig.fromConfig(config.getConfig("security")) else null,
                environment = environment
            )
        }

        /**
         * 加载配置文件
         */
        private fun loadConfig(environment: String): Config {
            // 首先检查是否通过系统属性指定了配置文件
            val configFile = System.getProperty("config.file")
            val config = if (configFile != null) {
                ConfigFactory.parseFile(File(configFile))
            } else {
                // 否则尝试根据环境加载特定配置
                val envConfig = try {
                    ConfigFactory.load("application-$environment.conf")
                } catch (e: Exception) {
                    // 如果特定环境配置不存在，回退到默认配置
                    ConfigFactory.empty()
                }
                
                // 加载默认配置，并与环境特定配置合并
                val defaultConfig = ConfigFactory.load("application.conf")
                envConfig.withFallback(defaultConfig)
            }
            
            return ConfigFactory.systemProperties()
                .withFallback(config)
                .resolve()
        }
    }
}

/**
 * API配置
 */
data class ApiConfig(
    val host: String,
    val port: Int,
    val enableCors: Boolean,
    val requestTimeoutMs: Long
) {
    companion object {
        fun fromConfig(config: Config): ApiConfig = ApiConfig(
            host = if (config.hasPath("host")) config.getString("host") else "0.0.0.0",
            port = if (config.hasPath("port")) config.getInt("port") else 8080,
            enableCors = if (config.hasPath("enable-cors")) config.getBoolean("enable-cors") else true,
            requestTimeoutMs = if (config.hasPath("request-timeout-ms")) config.getLong("request-timeout-ms") else 30000
        )
    }
}

/**
 * Disruptor配置
 */
data class DisruptorConfig(
    val bufferSize: Int,
    val waitStrategy: String,
    val producerType: String
) {
    companion object {
        fun fromConfig(config: Config): DisruptorConfig = DisruptorConfig(
            bufferSize = config.getInt("buffer-size"),
            waitStrategy = config.getString("wait-strategy"),
            producerType = config.getString("producer-type")
        )
    }
}

/**
 * Akka配置
 */
data class AkkaConfig(
    val clusterEnabled: Boolean,
    val seedNodes: List<String>
) {
    companion object {
        fun fromConfig(config: Config): AkkaConfig = AkkaConfig(
            clusterEnabled = config.getBoolean("cluster-enabled"),
            seedNodes = if (config.hasPath("seed-nodes")) 
                config.getStringList("seed-nodes") 
            else 
                emptyList()
        )
    }
}

/**
 * 数据库配置
 */
data class DbConfig(
    val url: String,
    val username: String,
    val password: String,
    val poolSize: Int
) {
    companion object {
        fun fromConfig(config: Config): DbConfig = DbConfig(
            url = config.getString("url"),
            username = config.getString("username"),
            password = config.getString("password"),
            poolSize = config.getInt("pool-size")
        )
    }
}

/**
 * 交易引擎配置
 */
data class EngineConfig(
    val maxOrdersPerBook: Int,
    val maxInstruments: Int,
    val snapshotInterval: Int,
    val cleanupIdleActorsAfterMinutes: Int
) {
    companion object {
        fun fromConfig(config: Config): EngineConfig = EngineConfig(
            maxOrdersPerBook = config.getInt("max-orders-per-book"),
            maxInstruments = config.getInt("max-instruments"),
            snapshotInterval = config.getInt("snapshot-interval"),
            cleanupIdleActorsAfterMinutes = config.getInt("cleanup-idle-actors-after-minutes")
        )
    }
}

/**
 * 恢复服务配置
 */
data class RecoveryConfig(
    val enabled: Boolean,
    val includeEventsBeforeSnapshot: Boolean,
    val eventsBeforeSnapshotTimeWindowMs: Long,
    val autoStartSnapshots: Boolean
) {
    companion object {
        fun fromConfig(config: Config): RecoveryConfig = RecoveryConfig(
            enabled = config.getBoolean("enabled"),
            includeEventsBeforeSnapshot = config.getBoolean("include-events-before-snapshot"),
            eventsBeforeSnapshotTimeWindowMs = config.getLong("events-before-snapshot-time-window-ms"),
            autoStartSnapshots = config.getBoolean("auto-start-snapshots")
        )
    }
}

/**
 * 监控配置
 */
data class MonitoringConfig(
    val prometheusEnabled: Boolean,
    val prometheusPort: Int,
    val metricsIntervalSeconds: Int
) {
    companion object {
        fun fromConfig(config: Config): MonitoringConfig = MonitoringConfig(
            prometheusEnabled = config.getBoolean("prometheus-enabled"),
            prometheusPort = config.getInt("prometheus-port"),
            metricsIntervalSeconds = config.getInt("metrics-interval-seconds")
        )
    }
}

/**
 * 性能优化配置
 */
data class PerformanceConfig(
    val jvmOptimizationsEnabled: Boolean,
    val gcLogDir: String,
    val offHeapEnabled: Boolean,
    val offHeapSizeMb: Int
) {
    companion object {
        fun fromConfig(config: Config): PerformanceConfig = PerformanceConfig(
            jvmOptimizationsEnabled = config.getBoolean("jvm-optimizations-enabled"),
            gcLogDir = config.getString("gc-log-dir"),
            offHeapEnabled = config.getBoolean("off-heap-enabled"),
            offHeapSizeMb = config.getInt("off-heap-size-mb")
        )
    }
}

/**
 * 安全配置
 */
data class SecurityConfig(
    val sslEnabled: Boolean,
    val sslCertificate: String,
    val sslPrivateKey: String,
    val apiKeyAuthEnabled: Boolean,
    val ipWhitelistEnabled: Boolean,
    val allowedIps: List<String>
) {
    companion object {
        fun fromConfig(config: Config): SecurityConfig = SecurityConfig(
            sslEnabled = config.getBoolean("ssl-enabled"),
            sslCertificate = config.getString("ssl-certificate"),
            sslPrivateKey = config.getString("ssl-private-key"),
            apiKeyAuthEnabled = config.getBoolean("api-key-auth-enabled"),
            ipWhitelistEnabled = config.getBoolean("ip-whitelist-enabled"),
            allowedIps = config.getStringList("allowed-ips")
        )
    }
} 