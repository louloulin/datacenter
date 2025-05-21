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
    val monitoring: MonitoringConfig
) {
    companion object {
        /**
         * 从配置文件加载配置
         */
        fun load(): AppConfig {
            val config = loadConfig()
            return AppConfig(
                disruptor = DisruptorConfig.fromConfig(config.getConfig("disruptor")),
                akka = AkkaConfig.fromConfig(config.getConfig("akka")),
                db = DbConfig.fromConfig(config.getConfig("db")),
                engine = EngineConfig.fromConfig(config.getConfig("engine")),
                recovery = RecoveryConfig.fromConfig(config.getConfig("recovery")),
                api = ApiConfig.fromConfig(config.getConfig("api")),
                monitoring = MonitoringConfig.fromConfig(config.getConfig("monitoring"))
            )
        }

        /**
         * 加载配置文件
         */
        private fun loadConfig(): Config {
            val configFile = System.getProperty("config.file")
            return if (configFile != null) {
                ConfigFactory.parseFile(File(configFile)).withFallback(ConfigFactory.load())
            } else {
                ConfigFactory.load()
            }
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