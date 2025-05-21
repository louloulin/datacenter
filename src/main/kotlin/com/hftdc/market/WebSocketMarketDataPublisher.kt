package com.hftdc.market

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * WebSocket市场数据发布器 - 通过WebSocket发布市场数据
 */
class WebSocketMarketDataPublisher(
    private val marketDataProcessor: MarketDataProcessor
) {
    // 模拟的WebSocket连接，以客户端ID为键
    private val connections = ConcurrentHashMap<String, WebSocketConnection>()
    
    // JSON序列化配置
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * 添加WebSocket连接
     */
    fun addConnection(clientId: String, connection: WebSocketConnection) {
        connections[clientId] = connection
        logger.info { "WebSocket连接已添加: $clientId" }
    }
    
    /**
     * 移除WebSocket连接
     */
    fun removeConnection(clientId: String) {
        connections.remove(clientId)
        logger.info { "WebSocket连接已移除: $clientId" }
    }
    
    /**
     * 处理订阅请求
     */
    fun handleSubscription(clientId: String, subscription: MarketDataSubscription) {
        val connection = connections[clientId] ?: run {
            logger.warn { "无法找到WebSocket连接: $clientId" }
            return
        }
        
        val subscriber = ConnectionSubscriber(connection, subscription)
        marketDataProcessor.subscribe(subscription, subscriber)
        
        connection.addSubscription(subscription, subscriber)
        logger.info { "客户端 $clientId 订阅了: ${subscription.instrumentId}" }
    }
    
    /**
     * 处理取消订阅请求
     */
    fun handleUnsubscription(clientId: String, instrumentId: String) {
        val connection = connections[clientId] ?: run {
            logger.warn { "无法找到WebSocket连接: $clientId" }
            return
        }
        
        connection.getSubscriptionByInstrument(instrumentId)?.let { pair ->
            val (subscription, subscriber) = pair
            marketDataProcessor.unsubscribe(subscription, subscriber)
            connection.removeSubscription(instrumentId)
            logger.info { "客户端 $clientId 取消订阅了: $instrumentId" }
        }
    }
    
    /**
     * 关闭发布器
     */
    fun shutdown() {
        connections.values.forEach { it.close() }
        connections.clear()
        logger.info { "WebSocket市场数据发布器已关闭" }
    }
    
    /**
     * 连接订阅者 - 实现MarketDataSubscriber接口
     */
    private inner class ConnectionSubscriber(
        private val connection: WebSocketConnection,
        override val subscription: MarketDataSubscription
    ) : MarketDataSubscriber {
        
        override fun onMarketDataUpdate(update: MarketDataUpdate) {
            try {
                val message = json.encodeToString(update)
                connection.send(message)
            } catch (e: Exception) {
                logger.error(e) { "发送市场数据更新失败: ${update.instrumentId}" }
            }
        }
        
        override fun onStatistics(statistics: MarketStatistics) {
            try {
                val message = json.encodeToString(statistics)
                connection.send(message)
            } catch (e: Exception) {
                logger.error(e) { "发送统计数据失败: ${statistics.instrumentId}" }
            }
        }
    }
}

/**
 * WebSocket连接接口
 */
interface WebSocketConnection {
    fun send(message: String)
    fun close()
    fun addSubscription(subscription: MarketDataSubscription, subscriber: MarketDataSubscriber)
    fun removeSubscription(instrumentId: String)
    fun getSubscriptionByInstrument(instrumentId: String): Pair<MarketDataSubscription, MarketDataSubscriber>?
}

/**
 * 模拟的WebSocket连接实现
 */
class MockWebSocketConnection(private val clientId: String) : WebSocketConnection {
    private val subscriptions = ConcurrentHashMap<String, Pair<MarketDataSubscription, MarketDataSubscriber>>()
    
    override fun send(message: String) {
        // 模拟发送消息，实际实现中应该使用真实的WebSocket发送
        logger.debug { "发送到客户端 $clientId: $message" }
    }
    
    override fun close() {
        // 模拟关闭连接
        logger.info { "关闭客户端 $clientId 的连接" }
    }
    
    override fun addSubscription(subscription: MarketDataSubscription, subscriber: MarketDataSubscriber) {
        subscriptions[subscription.instrumentId] = subscription to subscriber
    }
    
    override fun removeSubscription(instrumentId: String) {
        subscriptions.remove(instrumentId)
    }
    
    override fun getSubscriptionByInstrument(instrumentId: String): Pair<MarketDataSubscription, MarketDataSubscriber>? {
        return subscriptions[instrumentId]
    }
} 