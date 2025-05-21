package com.hftdc.config.dsl

import com.lmax.disruptor.dsl.ProducerType
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DSL使用示例
 */
fun main() {
    // 使用DSL创建配置
    val config = tradingPlatform {
        server {
            host = "0.0.0.0"
            port = 8080
            enableSsl = true
            requestTimeoutMs = 15000
        }
        
        disruptor {
            bufferSize = 16384
            waitStrategy = WaitStrategy.BUSY_SPIN
            producerType = ProducerType.MULTI
        }
        
        engine {
            maxOrdersPerBook = 1000000
            maxInstruments = 500
            snapshotInterval = 30.seconds
            cleanupIdleActorsAfterMinutes = 15
            
            // 配置特定交易品种的订单簿
            orderBook("BTC-USDT") {
                pricePrecision = 2
                quantityPrecision = 8
                priceTickSize = 0.01
                quantityMinimum = 0.0001
            }
            
            orderBook("ETH-USDT") {
                pricePrecision = 2
                quantityPrecision = 6
                priceTickSize = 0.01
                quantityMinimum = 0.001
            }
        }
        
        monitoring {
            prometheus {
                enabled = true
                port = 9090
            }
            metrics {
                collectInterval = 5.seconds
                jvmMetricsEnabled = true
            }
        }
        
        security {
            apiKeyAuthEnabled = true
            ipWhitelistEnabled = true
            allowedIps = listOf("10.0.0.1", "10.0.0.2")
            
            ssl {
                certificatePath = "/path/to/cert"
                privateKeyPath = "/path/to/key"
            }
        }
        
        recovery {
            enabled = true
            includeEventsBeforeSnapshot = true
            eventsBeforeSnapshotTimeWindowMs = 5000
            autoStartSnapshots = true
        }
        
        journaling {
            baseDir = "/data/hftdc/journal"
            snapshotDir = "/data/hftdc/snapshots"
            flushIntervalMs = 500
        }
        
        marketData {
            source("internal") {
                instruments = listOf("BTC-USDT", "ETH-USDT")
                aggregationLevel = 1.seconds
            }
            
            source("external") {
                endpoint = "wss://market.example.com/ws"
                instruments = listOf("BTC-USDT", "ETH-USDT", "SOL-USDT")
                reconnectInterval = 5.seconds
            }
            
            publisher("websocket") {
                port = 8081
                path = "/market"
                throttleInterval = 100.milliseconds
            }
        }
    }
    
    // 打印配置示例
    println("服务器配置: ${config.server.host}:${config.server.port}")
    println("Disruptor缓冲区大小: ${config.disruptor.bufferSize}")
    println("交易品种数量: ${config.engine.getOrderBooks().size}")
    println("已配置的交易品种: ${config.engine.getOrderBooks().keys.joinToString(", ")}")
    println("市场数据来源: ${config.marketData.getSources().keys.joinToString(", ")}")
    
    // 实际应用中，这个配置会被传递给应用程序上下文
    // val context = TradingPlatformContextImpl(config)
    // context.start()
}

/**
 * 如何集成到现有配置:
 * 
 * 1. 创建一个从PlatformConfig到现有AppConfig的转换函数
 */
/*
fun PlatformConfig.toAppConfig(): AppConfig {
    return AppConfig(
        disruptor = DisruptorConfig(
            bufferSize = this.disruptor.bufferSize,
            waitStrategy = convertWaitStrategy(this.disruptor.waitStrategy),
            producerType = this.disruptor.producerType.name
        ),
        akka = AkkaConfig(
            clusterEnabled = true,
            seedNodes = listOf("akka://hftdc@node1:2551")
        ),
        // ... 其他配置转换
        environment = "production"
    )
}

private fun convertWaitStrategy(strategy: WaitStrategy): String {
    return when(strategy) {
        WaitStrategy.BLOCKING -> "blocking"
        WaitStrategy.BUSY_SPIN -> "busySpin"
        WaitStrategy.YIELDING -> "yielding"
        WaitStrategy.SLEEPING -> "sleeping"
    }
}
*/ 