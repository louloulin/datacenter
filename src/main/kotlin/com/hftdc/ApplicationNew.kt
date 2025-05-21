package com.hftdc

import com.hftdc.config.dsl.tradingPlatform
import com.hftdc.core.TradingPlatformContextImpl
import mu.KotlinLogging
import kotlin.time.Duration.Companion.seconds

/**
 * 基于新框架设计的应用程序入口点
 */
private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "启动高频交易数据中心 (框架化版本)" }
    
    try {
        // 使用DSL构建配置
        val config = tradingPlatform {
            // 服务器配置
            server {
                host = "0.0.0.0"
                port = 8080
                enableSsl = false
                requestTimeoutMs = 30000
            }
            
            // Disruptor配置
            disruptor {
                bufferSize = 8192
                waitStrategy = com.hftdc.config.dsl.WaitStrategy.BUSY_SPIN
                producerType = com.lmax.disruptor.dsl.ProducerType.MULTI
            }
            
            // 引擎配置
            engine {
                maxOrdersPerBook = 1000000
                maxInstruments = 500
                snapshotInterval = 30.seconds
                cleanupIdleActorsAfterMinutes = 15
                
                // 预配置一些交易品种的订单簿
                orderBook("BTC-USDT") {
                    pricePrecision = 2
                    quantityPrecision = 8
                }
                
                orderBook("ETH-USDT") {
                    pricePrecision = 2
                    quantityPrecision = 6
                }
            }
            
            // 监控配置
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
            
            // 恢复配置
            recovery {
                enabled = true
                includeEventsBeforeSnapshot = true
                eventsBeforeSnapshotTimeWindowMs = 5000
                autoStartSnapshots = true
            }
            
            // 日志配置
            journaling {
                baseDir = "data/journal"
                snapshotDir = "data/snapshots"
                flushIntervalMs = 500
            }
            
            // 市场数据配置
            marketData {
                source("internal") {
                    instruments = listOf("BTC-USDT", "ETH-USDT")
                    aggregationLevel = 1.seconds
                }
                
                publisher("websocket") {
                    port = 8081
                    path = "/market"
                    throttleInterval = 100.seconds
                }
            }
        }
        
        // 创建应用上下文
        val context = TradingPlatformContextImpl(config)
        
        // 注册插件 (如果有)
        // context.registerPlugin(MyCustomPlugin())
        
        // 启动应用
        context.start()
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info { "关闭中..." }
            context.stop()
        })
        
        logger.info { "高频交易数据中心启动成功" }
        
    } catch (e: Exception) {
        logger.error(e) { "启动应用失败" }
        System.exit(1)
    }
} 