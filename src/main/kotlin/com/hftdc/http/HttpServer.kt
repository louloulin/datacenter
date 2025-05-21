package com.hftdc.http

import com.hftdc.api.TradingApi
import com.hftdc.engine.OrderBookManager
import com.hftdc.journal.JournalService
import com.hftdc.journal.RecoveryService
import com.hftdc.model.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * HTTP服务器配置
 */
data class HttpServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val enableCors: Boolean = true,
    val requestTimeoutMs: Long = 30000
)

/**
 * HTTP服务器 - 提供REST API接口
 */
class HttpServer(
    private val config: HttpServerConfig,
    private val tradingApi: TradingApi,
    private val orderBookManager: OrderBookManager,
    private val journalService: JournalService? = null,
    private val recoveryService: RecoveryService? = null
) {
    private lateinit var server: NettyApplicationEngine
    
    /**
     * 启动HTTP服务器
     */
    fun start() {
        logger.info { "启动HTTP服务器 ${config.host}:${config.port}" }
        
        server = embeddedServer(Netty, port = config.port, host = config.host) {
            configureServer()
        }
        
        server.start(wait = false)
    }
    
    /**
     * 停止HTTP服务器
     */
    fun stop() {
        logger.info { "停止HTTP服务器" }
        server.stop(1000, 5000, TimeUnit.MILLISECONDS)
    }
    
    /**
     * 配置服务器
     */
    private fun Application.configureServer() {
        // 内容协商 - JSON序列化
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        
        // 跨域支持
        if (config.enableCors) {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
            }
        }
        
        // 状态页面
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                logger.error(cause) { "处理请求时发生错误" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (cause.message ?: "Internal Server Error"))
                )
            }
        }
        
        // 路由配置
        routing {
            // 健康检查
            get("/health") {
                call.respond(mapOf("status" to "UP", "timestamp" to Instant.now().toEpochMilli()))
            }
            
            // 交易API路由
            tradingRoutes()
            
            // 系统API路由
            systemRoutes()
        }
    }
    
    /**
     * 交易相关路由
     */
    private fun Routing.tradingRoutes() {
        route("/api/v1/trading") {
            // 下单
            post("/orders") {
                val request = call.receive<TradingApi.PlaceOrderRequest>()
                val response = awaitResponse(tradingApi.placeOrder(request))
                call.respond(HttpStatusCode.Created, response)
            }
            
            // 取消订单
            delete("/orders/{orderId}") {
                val orderId = call.parameters["orderId"]?.toLongOrNull() 
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid order ID"))
                
                val userId = call.request.queryParameters["userId"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing or invalid user ID"))
                
                val request = TradingApi.CancelOrderRequest(userId, orderId)
                val response = awaitResponse(tradingApi.cancelOrder(request))
                
                if (response.success) {
                    call.respond(HttpStatusCode.OK, response)
                } else {
                    call.respond(HttpStatusCode.NotFound, response)
                }
            }
            
            // 查询订单
            get("/orders/{orderId}") {
                val orderId = call.parameters["orderId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid order ID"))
                
                val userId = call.request.queryParameters["userId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing or invalid user ID"))
                
                val request = TradingApi.QueryOrderRequest(userId, orderId)
                val order = awaitResponse(tradingApi.queryOrder(request))
                
                if (order != null) {
                    call.respond(HttpStatusCode.OK, order)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Order not found"))
                }
            }
            
            // 查询订单簿
            get("/orderbooks/{instrumentId}") {
                val instrumentId = call.parameters["instrumentId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing instrument ID"))
                
                val depth = call.request.queryParameters["depth"]?.toIntOrNull() ?: 10
                
                val request = TradingApi.OrderBookRequest(instrumentId, depth)
                val orderBook = awaitResponse(tradingApi.getOrderBook(request))
                
                call.respond(HttpStatusCode.OK, orderBook)
            }
        }
    }
    
    /**
     * 系统相关路由
     */
    private fun Routing.systemRoutes() {
        route("/api/v1/system") {
            // 系统状态
            get("/status") {
                val status = awaitResponse(tradingApi.getSystemStatus())
                call.respond(HttpStatusCode.OK, status)
            }
            
            // 活跃交易品种
            get("/instruments") {
                val instruments = orderBookManager.getActiveInstrumentIds()
                call.respond(HttpStatusCode.OK, mapOf("instruments" to instruments))
            }
            
            // 创建快照（管理员操作）
            post("/snapshots") {
                if (journalService == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Journal service not available"))
                    return@post
                }
                
                val request = call.receive<CreateSnapshotRequest>()
                val instrumentId = request.instrumentId
                
                if (instrumentId == null || instrumentId == "*") {
                    // 为所有活跃的交易品种创建快照
                    val instruments = orderBookManager.getActiveInstrumentIds()
                    val results = mutableMapOf<String, String>()
                    
                    instruments.forEach { id ->
                        try {
                            val orderBook = orderBookManager.getOrderBook(id)
                            val snapshot = orderBook.getSnapshot(request.depth ?: 10)
                            val snapshotId = journalService.saveSnapshot(id, snapshot)
                            results[id] = snapshotId
                        } catch (e: Exception) {
                            logger.error(e) { "为交易品种 $id 创建快照失败" }
                            results[id] = "ERROR: ${e.message}"
                        }
                    }
                    
                    call.respond(HttpStatusCode.OK, mapOf("snapshots" to results))
                } else {
                    // 为指定的交易品种创建快照
                    try {
                        if (!orderBookManager.hasInstrument(instrumentId)) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Instrument not found: $instrumentId"))
                            return@post
                        }
                        
                        val orderBook = orderBookManager.getOrderBook(instrumentId)
                        val snapshot = orderBook.getSnapshot(request.depth ?: 10)
                        val snapshotId = journalService.saveSnapshot(instrumentId, snapshot)
                        
                        call.respond(HttpStatusCode.OK, mapOf("instrumentId" to instrumentId, "snapshotId" to snapshotId))
                    } catch (e: Exception) {
                        logger.error(e) { "为交易品种 $instrumentId 创建快照失败" }
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to create snapshot: ${e.message}")
                        )
                    }
                }
            }
            
            // 系统恢复（管理员操作）
            post("/recover") {
                if (recoveryService == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Recovery service not available"))
                    return@post
                }
                
                try {
                    val stats = recoveryService.recover()
                    call.respond(HttpStatusCode.OK, stats)
                } catch (e: Exception) {
                    logger.error(e) { "系统恢复失败" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Recovery failed: ${e.message}")
                    )
                }
            }
        }
    }
    
    /**
     * 等待CompletionStage完成，并返回结果
     */
    private suspend fun <T> awaitResponse(future: CompletionStage<T>): T {
        return withContext(Dispatchers.IO) {
            future.toCompletableFuture().get()
        }
    }
    
    /**
     * 创建快照请求
     */
    @Serializable
    data class CreateSnapshotRequest(
        val instrumentId: String? = null, // 如果为null或"*"，则为所有活跃的交易品种创建快照
        val depth: Int? = null // 快照深度，默认为10
    )
} 