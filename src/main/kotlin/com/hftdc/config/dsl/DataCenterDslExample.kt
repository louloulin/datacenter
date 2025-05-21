package com.hftdc.config.dsl

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 数据中心特性DSL示例
 * 展示如何通过Kotlin DSL配置高频交易数据中心的各个方面
 */

// 集群模式枚举
enum class ClusterMode {
    STANDALONE,    // 单节点模式
    ACTIVE_PASSIVE, // 主备模式
    DISTRIBUTED     // 分布式模式
}

// 状态复制策略
enum class StateReplicationStrategy {
    NONE,           // 不复制
    ACTIVE_PASSIVE, // 主备复制
    ACTIVE_ACTIVE   // 多活复制
}

// 警报严重性级别
enum class AlertSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * 交易数据中心DSL根配置
 */
@TradingPlatformDsl
class DataCenterConfig : PlatformConfig() {
    var dataStorage: DataStorageConfig = DataStorageConfig()
    var cluster: ClusterConfig = ClusterConfig()
    var analytics: AnalyticsConfig = AnalyticsConfig()
}

/**
 * 数据存储配置
 */
@TradingPlatformDsl
class DataStorageConfig {
    var timeSeriesDb: TimeSeriesDbConfig = TimeSeriesDbConfig()
    var documentDb: DocumentDbConfig = DocumentDbConfig()
    var historicalData: HistoricalDataConfig = HistoricalDataConfig()
    
    fun timeSeriesDb(init: TimeSeriesDbConfig.() -> Unit) {
        timeSeriesDb = TimeSeriesDbConfig().apply(init)
    }
    
    fun documentDb(init: DocumentDbConfig.() -> Unit) {
        documentDb = DocumentDbConfig().apply(init)
    }
    
    fun historicalData(init: HistoricalDataConfig.() -> Unit) {
        historicalData = HistoricalDataConfig().apply(init)
    }
}

/**
 * 时间序列数据库配置
 */
@TradingPlatformDsl
class TimeSeriesDbConfig {
    var type: String = "influxdb"
    var url: String = "http://localhost:8086"
    var database: String = "hftdc"
    var username: String = ""
    var password: String = ""
    var retentionPolicy: RetentionPolicyConfig = RetentionPolicyConfig()
    
    fun retentionPolicy(init: RetentionPolicyConfig.() -> Unit) {
        retentionPolicy = RetentionPolicyConfig().apply(init)
    }
}

/**
 * 保留策略配置
 */
@TradingPlatformDsl
class RetentionPolicyConfig {
    var name: String = "default"
    var duration: kotlin.time.Duration = 30.days
    var replication: Int = 1
    var shardDuration: kotlin.time.Duration = 1.days
}

/**
 * 文档数据库配置
 */
@TradingPlatformDsl
class DocumentDbConfig {
    var type: String = "mongodb"
    var connectionString: String = "mongodb://localhost:27017"
    var database: String = "hftdc_analytics"
    var username: String = ""
    var password: String = ""
    var replicaSet: Boolean = false
    var replicaSetName: String = ""
}

/**
 * 历史数据配置
 */
@TradingPlatformDsl
class HistoricalDataConfig {
    var partitionInterval: kotlin.time.Duration = 1.days
    var compressionEnabled: Boolean = true
    var compressionAlgorithm: String = "zstd"
    var coldStorageDays: Int = 30
    var backupEnabled: Boolean = true
    var backupInterval: kotlin.time.Duration = 1.days
}

/**
 * 集群配置
 */
@TradingPlatformDsl
class ClusterConfig {
    var mode: ClusterMode = ClusterMode.STANDALONE
    var nodeId: String = "node-1"
    var region: String = "default"
    var zones: List<String> = emptyList()
    var clusterDiscovery: ClusterDiscoveryConfig = ClusterDiscoveryConfig()
    var stateReplication: StateReplicationConfig = StateReplicationConfig()
    
    fun clusterDiscovery(init: ClusterDiscoveryConfig.() -> Unit) {
        clusterDiscovery = ClusterDiscoveryConfig().apply(init)
    }
    
    fun stateReplication(init: StateReplicationConfig.() -> Unit) {
        stateReplication = StateReplicationConfig().apply(init)
    }
}

/**
 * 集群发现配置
 */
@TradingPlatformDsl
class ClusterDiscoveryConfig {
    var type: String = "static"  // static, kubernetes, consul, etc.
    var staticNodes: List<String> = emptyList()
    var namespace: String = "default"
    var serviceName: String = "hftdc-cluster"
    var discoveryInterval: kotlin.time.Duration = 10.seconds
}

/**
 * 状态复制配置
 */
@TradingPlatformDsl
class StateReplicationConfig {
    var strategy: StateReplicationStrategy = StateReplicationStrategy.NONE
    var syncInterval: kotlin.time.Duration = 100.milliseconds
    var batchSize: Int = 100
    var compressionEnabled: Boolean = true
}

/**
 * 分析引擎配置
 */
@TradingPlatformDsl
class AnalyticsConfig {
    var streamProcessing: StreamProcessingConfig = StreamProcessingConfig()
    var alerting: AlertingConfig = AlertingConfig()
    var queries: QueryConfig = QueryConfig()
    
    fun streamProcessing(init: StreamProcessingConfig.() -> Unit) {
        streamProcessing = StreamProcessingConfig().apply(init)
    }
    
    fun alerting(init: AlertingConfig.() -> Unit) {
        alerting = AlertingConfig().apply(init)
    }
    
    fun queries(init: QueryConfig.() -> Unit) {
        queries = QueryConfig().apply(init)
    }
}

/**
 * 流处理配置
 */
@TradingPlatformDsl
class StreamProcessingConfig {
    var windowSize: kotlin.time.Duration = 5.minutes
    var slidingInterval: kotlin.time.Duration = 1.minutes
    var parallelism: Int = 4
    var checkpointInterval: kotlin.time.Duration = 1.minutes
    var bufferTimeout: kotlin.time.Duration = 100.milliseconds
}

/**
 * 告警配置
 */
@TradingPlatformDsl
class AlertingConfig {
    var enabled: Boolean = true
    var channels: List<String> = emptyList()
    var throttleInterval: kotlin.time.Duration = 5.minutes
    var defaultSeverity: AlertSeverity = AlertSeverity.MEDIUM
}

/**
 * 查询配置
 */
@TradingPlatformDsl
class QueryConfig {
    var maxConcurrent: Int = 10
    var timeoutMs: Long = 5000
    var maxResultRows: Int = 10000
    var cacheEnabled: Boolean = true
    var cacheTtl: kotlin.time.Duration = 5.minutes
}

/**
 * 创建数据中心配置的顶层函数
 */
fun tradingDataCenter(init: DataCenterConfig.() -> Unit): DataCenterConfig {
    return DataCenterConfig().apply(init)
}

/**
 * 数据存储配置DSL函数
 */
fun DataCenterConfig.dataStorage(init: DataStorageConfig.() -> Unit) {
    dataStorage = DataStorageConfig().apply(init)
}

/**
 * 集群配置DSL函数
 */
fun DataCenterConfig.cluster(init: ClusterConfig.() -> Unit) {
    cluster = ClusterConfig().apply(init)
}

/**
 * 分析引擎配置DSL函数
 */
fun DataCenterConfig.analytics(init: AnalyticsConfig.() -> Unit) {
    analytics = AnalyticsConfig().apply(init)
}

/**
 * 示例使用
 */
fun main() {
    val config = tradingDataCenter {
        // 服务器基本配置
        server {
            host = "0.0.0.0"
            port = 8080
            enableSsl = true
        }
        
        // 数据存储配置
        dataStorage {
            timeSeriesDb {
                type = "influxdb"
                url = "http://influxdb:8086"
                database = "hftdc_production"
                
                retentionPolicy {
                    name = "trading_data"
                    duration = 90.days
                    replication = 3
                    shardDuration = 1.days
                }
            }
            
            documentDb {
                type = "mongodb"
                connectionString = "mongodb://mongo-1:27017,mongo-2:27017,mongo-3:27017"
                database = "hftdc_analytics"
                replicaSet = true
                replicaSetName = "hftdc_rs"
            }
            
            historicalData {
                partitionInterval = 1.days
                compressionEnabled = true
                compressionAlgorithm = "zstd"
                coldStorageDays = 90
                backupEnabled = true
                backupInterval = 6.hours
            }
        }
        
        // 集群配置
        cluster {
            mode = ClusterMode.DISTRIBUTED
            nodeId = "node-1"
            region = "us-east"
            zones = listOf("us-east-1a", "us-east-1b")
            
            clusterDiscovery {
                type = "kubernetes"
                namespace = "hftdc-prod"
                serviceName = "hftdc-cluster"
                discoveryInterval = 5.seconds
            }
            
            stateReplication {
                strategy = StateReplicationStrategy.ACTIVE_ACTIVE
                syncInterval = 50.milliseconds
                batchSize = 200
                compressionEnabled = true
            }
        }
        
        // 分析引擎配置
        analytics {
            streamProcessing {
                windowSize = 5.minutes
                slidingInterval = 30.seconds
                parallelism = 16
                checkpointInterval = 30.seconds
            }
            
            alerting {
                enabled = true
                channels = listOf("slack", "email", "pagerduty")
                throttleInterval = 10.minutes
                defaultSeverity = AlertSeverity.HIGH
            }
            
            queries {
                maxConcurrent = 30
                timeoutMs = 10000
                maxResultRows = 50000
                cacheEnabled = true
                cacheTtl = 2.minutes
            }
        }
    }
    
    // 使用示例
    println("数据中心模式: ${config.cluster.mode}")
    println("时间序列数据库: ${config.dataStorage.timeSeriesDb.type}")
    println("数据保留期: ${config.dataStorage.timeSeriesDb.retentionPolicy.duration}")
    println("集群发现方式: ${config.cluster.clusterDiscovery.type}")
    println("处理窗口大小: ${config.analytics.streamProcessing.windowSize}")
    println("最大并发查询数: ${config.analytics.queries.maxConcurrent}")
} 