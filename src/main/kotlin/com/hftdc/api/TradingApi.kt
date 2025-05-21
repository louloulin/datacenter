package com.hftdc.api

import akka.actor.typed.ActorRef
import com.hftdc.core.ForwardOrder
import com.hftdc.core.GetSystemStatus
import com.hftdc.core.RootCommand
import com.hftdc.core.SystemStatus
import com.hftdc.engine.OrderBookManager
import com.hftdc.model.Order
import com.hftdc.model.OrderBookSnapshot
import com.hftdc.model.OrderSide
import com.hftdc.model.OrderStatus
import com.hftdc.model.OrderType
import com.hftdc.model.TimeInForce
import com.hftdc.model.Trade
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * 交易API - 提供交易相关的API接口
 */
class TradingApi(
    private val rootActor: ActorRef<RootCommand>,
    private val orderBookManager: OrderBookManager
) {
    
    // 订单ID生成器
    private val orderIdGenerator = AtomicLong(1)
    
    // 挂起的请求映射表
    private val pendingRequests = ConcurrentHashMap<Long, CompletableFuture<*>>()
    
    /**
     * 下单请求
     */
    @Serializable
    data class PlaceOrderRequest(
        val userId: Long,
        val instrumentId: String,
        val price: Long?,
        val quantity: Long,
        val side: OrderSide,
        val type: OrderType,
        val timeInForce: TimeInForce = TimeInForce.GTC
    )
    
    /**
     * 下单响应
     */
    @Serializable
    data class PlaceOrderResponse(
        val orderId: Long,
        val status: OrderStatus,
        val message: String? = null
    )
    
    /**
     * 取消订单请求
     */
    @Serializable
    data class CancelOrderRequest(
        val userId: Long,
        val orderId: Long
    )
    
    /**
     * 取消订单响应
     */
    @Serializable
    data class CancelOrderResponse(
        val success: Boolean,
        val message: String? = null
    )
    
    /**
     * 查询订单请求
     */
    @Serializable
    data class QueryOrderRequest(
        val userId: Long,
        val orderId: Long
    )
    
    /**
     * 查询订单簿请求
     */
    @Serializable
    data class OrderBookRequest(
        val instrumentId: String,
        val depth: Int = 10
    )
    
    /**
     * 下单
     */
    fun placeOrder(request: PlaceOrderRequest): CompletionStage<PlaceOrderResponse> {
        val future = CompletableFuture<PlaceOrderResponse>()
        
        try {
            // 生成订单ID
            val orderId = orderIdGenerator.getAndIncrement()
            
            // 创建订单对象
            val order = Order(
                id = orderId,
                userId = request.userId,
                instrumentId = request.instrumentId,
                price = request.price,
                quantity = request.quantity,
                remainingQuantity = request.quantity, // 初始时剩余数量等于总数量
                side = request.side,
                type = request.type,
                timeInForce = request.timeInForce,
                timestamp = Instant.now().toEpochMilli(),
                status = OrderStatus.NEW
            )
            
            // 添加到挂起请求映射表
            pendingRequests[orderId] = future
            
            // 转发到Actor系统处理
            rootActor.tell(ForwardOrder(order))
            
            logger.info { "提交订单: $orderId, 用户: ${request.userId}, 品种: ${request.instrumentId}" }
            
            // TODO: 完整实现应该等待订单处理结果，这里简化处理
            future.complete(PlaceOrderResponse(
                orderId = orderId,
                status = OrderStatus.NEW
            ))
        } catch (e: Exception) {
            logger.error(e) { "下单失败" }
            future.completeExceptionally(e)
        }
        
        return future
    }
    
    /**
     * 取消订单
     */
    fun cancelOrder(request: CancelOrderRequest): CompletionStage<CancelOrderResponse> {
        val future = CompletableFuture<CancelOrderResponse>()
        
        try {
            // 查找订单所在的订单簿
            var orderInstrumentId: String? = null
            var cancelledOrder: Order? = null
            
            // 遍历所有活跃订单簿，查找订单
            for (instrumentId in orderBookManager.getActiveInstrumentIds()) {
                val orderBook = orderBookManager.getOrderBook(instrumentId)
                val order = orderBook.getOrder(request.orderId)
                
                if (order != null && order.userId == request.userId) {
                    orderInstrumentId = instrumentId
                    break
                }
            }
            
            // 如果找到订单，尝试取消
            if (orderInstrumentId != null) {
                val orderBook = orderBookManager.getOrderBook(orderInstrumentId)
                cancelledOrder = orderBook.cancelOrder(request.orderId)
            }
            
            if (cancelledOrder != null) {
                logger.info { "取消订单成功: ${request.orderId}, 用户: ${request.userId}" }
                future.complete(CancelOrderResponse(
                    success = true
                ))
            } else {
                logger.warn { "取消订单失败: ${request.orderId}, 用户: ${request.userId}, 未找到订单或无法取消" }
                future.complete(CancelOrderResponse(
                    success = false,
                    message = "Order not found or cannot be canceled"
                ))
            }
        } catch (e: Exception) {
            logger.error(e) { "取消订单失败" }
            future.complete(CancelOrderResponse(
                success = false,
                message = e.message
            ))
        }
        
        return future
    }
    
    /**
     * 查询订单
     */
    fun queryOrder(request: QueryOrderRequest): CompletionStage<Order?> {
        val future = CompletableFuture<Order?>()
        
        try {
            // 遍历所有活跃订单簿，查找订单
            for (instrumentId in orderBookManager.getActiveInstrumentIds()) {
                val orderBook = orderBookManager.getOrderBook(instrumentId)
                val order = orderBook.getOrder(request.orderId)
                
                if (order != null && order.userId == request.userId) {
                    logger.info { "查询订单成功: ${request.orderId}, 用户: ${request.userId}" }
                    future.complete(order)
                    return future
                }
            }
            
            // 未找到订单
            logger.warn { "查询订单: ${request.orderId}, 用户: ${request.userId}, 未找到订单" }
            future.complete(null)
        } catch (e: Exception) {
            logger.error(e) { "查询订单失败" }
            future.completeExceptionally(e)
        }
        
        return future
    }
    
    /**
     * 查询订单簿
     */
    fun getOrderBook(request: OrderBookRequest): CompletionStage<OrderBookSnapshot> {
        val future = CompletableFuture<OrderBookSnapshot>()
        
        try {
            val instrumentId = request.instrumentId
            val depth = request.depth
            
            // 检查交易品种是否存在
            if (!orderBookManager.hasInstrument(instrumentId)) {
                // 如果不存在，创建一个空的订单簿
                logger.info { "查询订单簿: $instrumentId, 交易品种不存在，返回空订单簿" }
                future.complete(OrderBookSnapshot(
                    instrumentId = instrumentId,
                    bids = emptyList(),
                    asks = emptyList(),
                    timestamp = Instant.now().toEpochMilli()
                ))
            } else {
                // 获取订单簿并生成快照
                val orderBook = orderBookManager.getOrderBook(instrumentId)
                val snapshot = orderBook.getSnapshot(depth)
                
                logger.info { "查询订单簿成功: $instrumentId, 深度: $depth" }
                future.complete(snapshot)
            }
        } catch (e: Exception) {
            logger.error(e) { "查询订单簿失败" }
            future.completeExceptionally(e)
        }
        
        return future
    }
    
    /**
     * 查询系统状态
     */
    fun getSystemStatus(): CompletionStage<SystemStatus> {
        val future = CompletableFuture<SystemStatus>()
        
        try {
            // 收集系统状态信息
            val orderBookCount = orderBookManager.getOrderBookCount()
            val totalOrderCount = orderBookManager.getTotalOrderCount()
            val activeInstruments = orderBookManager.getActiveInstrumentIds().toList()
            
            val status = SystemStatus(
                orderBookCount = orderBookCount,
                totalOrderCount = totalOrderCount,
                activeInstruments = activeInstruments
            )
            
            logger.info { "查询系统状态成功: 订单簿数量=$orderBookCount, 订单总数=$totalOrderCount" }
            future.complete(status)
        } catch (e: Exception) {
            logger.error(e) { "查询系统状态失败" }
            future.completeExceptionally(e)
        }
        
        return future
    }
} 