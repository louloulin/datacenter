package com.hftdc.metrics

import com.hftdc.model.Order
import com.hftdc.model.OrderStatus
import com.hftdc.model.Trade
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 订单指标处理器 - 用于记录订单处理相关的指标
 */
class OrderMetricsHandler {
    private val metricsRegistry = MetricsRegistry
    
    // 订单处理开始时间记录
    private val orderProcessingStartTimes = mutableMapOf<Long, Long>()
    
    /**
     * 记录订单接收指标
     */
    fun recordOrderReceived(order: Order) {
        try {
            // 记录订单接收时间，用于后续计算处理延迟
            orderProcessingStartTimes[order.id] = Instant.now().toEpochMilli()
            
            // 增加订单处理计数
            metricsRegistry.recordOrderProcessed(
                instrumentId = order.instrumentId,
                side = order.side.name.lowercase(),
                type = order.type.name.lowercase()
            )
        } catch (e: Exception) {
            logger.error(e) { "Error recording order received metrics for order ${order.id}" }
        }
    }
    
    /**
     * 记录订单执行指标
     */
    fun recordOrderExecuted(order: Order, trades: List<Trade>) {
        try {
            // 记录订单执行
            metricsRegistry.recordOrderExecuted(
                instrumentId = order.instrumentId,
                side = order.side.name.lowercase()
            )
            
            // 记录成交量
            val totalVolume = trades.sumOf { it.quantity }
            metricsRegistry.recordTradeVolume(order.instrumentId, totalVolume)
            
            // 计算并记录订单处理延迟
            orderProcessingStartTimes[order.id]?.let { startTime ->
                val endTime = Instant.now().toEpochMilli()
                val processingTimeSeconds = (endTime - startTime) / 1000.0
                
                metricsRegistry.recordOrderProcessingTime(
                    instrumentId = order.instrumentId,
                    type = order.type.name.lowercase(),
                    timeInSeconds = processingTimeSeconds
                )
                
                // 清理开始时间记录
                orderProcessingStartTimes.remove(order.id)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error recording order executed metrics for order ${order.id}" }
        }
    }
    
    /**
     * 记录订单取消指标
     */
    fun recordOrderCancelled(order: Order) {
        try {
            metricsRegistry.recordOrderCancelled(
                instrumentId = order.instrumentId,
                side = order.side.name.lowercase()
            )
            
            // 计算并记录订单处理延迟
            orderProcessingStartTimes[order.id]?.let { startTime ->
                val endTime = Instant.now().toEpochMilli()
                val processingTimeSeconds = (endTime - startTime) / 1000.0
                
                metricsRegistry.recordOrderProcessingTime(
                    instrumentId = order.instrumentId,
                    type = order.type.name.lowercase(),
                    timeInSeconds = processingTimeSeconds
                )
                
                // 清理开始时间记录
                orderProcessingStartTimes.remove(order.id)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error recording order cancelled metrics for order ${order.id}" }
        }
    }
    
    /**
     * 记录订单完成指标（已完全成交、已取消或已拒绝）
     */
    fun recordOrderCompleted(order: Order) {
        try {
            // 如果订单处理结束，但仍有开始时间记录，计算并记录处理时间
            orderProcessingStartTimes[order.id]?.let { startTime ->
                val endTime = Instant.now().toEpochMilli()
                val processingTimeSeconds = (endTime - startTime) / 1000.0
                
                metricsRegistry.recordOrderProcessingTime(
                    instrumentId = order.instrumentId,
                    type = order.type.name.lowercase(),
                    timeInSeconds = processingTimeSeconds
                )
                
                // 清理开始时间记录
                orderProcessingStartTimes.remove(order.id)
            }
            
            // 记录订单相关指标
            when (order.status) {
                OrderStatus.FILLED -> {
                    // 订单已完全成交，不需要额外处理，因为在执行时已经记录过了
                }
                OrderStatus.PARTIALLY_FILLED -> {
                    // 部分成交，不需要额外处理，因为在执行时已经记录过了
                }
                OrderStatus.CANCELED -> {
                    // 已取消
                    metricsRegistry.recordOrderCancelled(
                        instrumentId = order.instrumentId,
                        side = order.side.name.lowercase()
                    )
                }
                OrderStatus.REJECTED -> {
                    // 被拒绝的订单
                    // 可以增加被拒绝的订单指标，如果有需要
                }
                else -> {
                    // 其他状态
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error recording order completed metrics for order ${order.id}" }
        }
    }
    
    /**
     * 清理过期的开始时间记录（防止内存泄漏）
     */
    fun cleanupStaleRecords(olderThanMs: Long = 3600000) { // 默认1小时
        try {
            val now = Instant.now().toEpochMilli()
            val staleThreshold = now - olderThanMs
            
            val staleOrderIds = orderProcessingStartTimes.entries
                .filter { it.value < staleThreshold }
                .map { it.key }
            
            staleOrderIds.forEach { orderId ->
                orderProcessingStartTimes.remove(orderId)
            }
            
            if (staleOrderIds.isNotEmpty()) {
                logger.debug { "Cleaned up ${staleOrderIds.size} stale order processing time records" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error cleaning up stale order processing time records" }
        }
    }
} 