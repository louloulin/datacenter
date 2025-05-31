package com.hftdc.disruptorx.gateway

import com.hftdc.disruptorx.monitoring.TradingMetricsCollector
import com.hftdc.disruptorx.security.SecurityManager
import com.hftdc.disruptorx.security.Permission
import com.hftdc.disruptorx.tracing.DistributedTracing
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.HttpResponseStatus.*
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.util.CharsetUtil
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * API请求
 */
@Serializable
data class ApiRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String? = null,
    val queryParams: Map<String, String> = emptyMap()
)

/**
 * API响应
 */
@Serializable
data class ApiResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

/**
 * 路由定义
 */
data class Route(
    val method: String,
    val path: String,
    val handler: suspend (ApiRequest, GatewayContext) -> ApiResponse,
    val requiredPermission: Permission? = null,
    val rateLimit: RateLimit? = null
)

/**
 * 限流配置
 */
data class RateLimit(
    val requestsPerSecond: Int,
    val burstSize: Int = requestsPerSecond * 2
)

/**
 * 网关上下文
 */
class GatewayContext(
    val requestId: String,
    val userId: String?,
    val userToken: String?,
    val startTime: Long = System.nanoTime()
)

/**
 * API网关
 */
class ApiGateway(
    private val port: Int,
    private val securityManager: SecurityManager,
    private val metricsCollector: TradingMetricsCollector,
    private val distributedTracing: DistributedTracing
) {
    private val routes = mutableListOf<Route>()
    private val rateLimiters = ConcurrentHashMap<String, RateLimiter>()
    private val requestIdGenerator = AtomicLong(0)
    
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null
    
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    /**
     * 添加路由
     */
    fun addRoute(route: Route) {
        routes.add(route)
    }
    
    /**
     * 启动网关
     */
    suspend fun start() {
        bossGroup = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup()
        
        try {
            val bootstrap = ServerBootstrap()
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        val pipeline = ch.pipeline()
                        pipeline.addLast("timeout", ReadTimeoutHandler(30))
                        pipeline.addLast("codec", HttpServerCodec())
                        pipeline.addLast("aggregator", HttpObjectAggregator(1048576))
                        pipeline.addLast("handler", ApiGatewayHandler())
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
            
            val future = bootstrap.bind(port).sync()
            channel = future.channel()
            
            println("API网关启动在端口: $port")
            
        } catch (e: Exception) {
            shutdown()
            throw e
        }
    }
    
    /**
     * 关闭网关
     */
    fun shutdown() {
        channel?.close()
        workerGroup?.shutdownGracefully()
        bossGroup?.shutdownGracefully()
    }
    
    /**
     * 处理HTTP请求
     */
    private suspend fun handleRequest(
        httpRequest: FullHttpRequest,
        ctx: ChannelHandlerContext
    ) {
        val requestId = generateRequestId()
        val startTime = System.nanoTime()
        
        val traceContext = distributedTracing.startTrace("api-gateway-request")
        distributedTracing.addTag("request.id", requestId)
        distributedTracing.addTag("request.method", httpRequest.method().name())
        distributedTracing.addTag("request.uri", httpRequest.uri())
        
        try {
            // 解析请求
            val apiRequest = parseHttpRequest(httpRequest)
            distributedTracing.addTag("request.path", apiRequest.path)
            
            // 认证和授权
            val gatewayContext = authenticateRequest(apiRequest, requestId)
            
            // 限流检查
            if (!checkRateLimit(gatewayContext.userId ?: "anonymous", apiRequest.path)) {
                sendResponse(ctx, createErrorResponse(429, "Rate limit exceeded"))
                return
            }
            
            // 路由匹配
            val route = findRoute(apiRequest.method, apiRequest.path)
            if (route == null) {
                sendResponse(ctx, createErrorResponse(404, "Route not found"))
                return
            }
            
            // 权限检查
            if (route.requiredPermission != null) {
                val hasPermission = gatewayContext.userToken?.let { token ->
                    securityManager.hasPermission(token, route.requiredPermission)
                } ?: false
                
                if (!hasPermission) {
                    sendResponse(ctx, createErrorResponse(403, "Permission denied"))
                    return
                }
            }
            
            // 执行处理器
            distributedTracing.addLog("开始执行路由处理器")
            val response = route.handler(apiRequest, gatewayContext)
            
            // 发送响应
            sendResponse(ctx, response)
            
            // 记录成功指标
            val latency = System.nanoTime() - startTime
            metricsCollector.recordTradeLatency("api-gateway-request", latency)
            metricsCollector.recordThroughput("api-requests", 1)
            
            distributedTracing.addLog("请求处理完成")
            
        } catch (e: SecurityException) {
            distributedTracing.addLog("安全异常: ${e.message}")
            sendResponse(ctx, createErrorResponse(401, "Authentication failed"))
            
        } catch (e: Exception) {
            distributedTracing.addLog("请求处理异常: ${e.message}")
            sendResponse(ctx, createErrorResponse(500, "Internal server error"))
            
        } finally {
            val totalLatency = System.nanoTime() - startTime
            metricsCollector.recordTradeLatency("api-gateway-total", totalLatency)
        }
    }
    
    /**
     * 解析HTTP请求
     */
    private fun parseHttpRequest(httpRequest: FullHttpRequest): ApiRequest {
        val uri = httpRequest.uri()
        val queryStringDecoder = QueryStringDecoder(uri)
        val path = queryStringDecoder.path()
        val queryParams = queryStringDecoder.parameters().mapValues { it.value.firstOrNull() ?: "" }
        
        val headers = mutableMapOf<String, String>()
        httpRequest.headers().forEach { header ->
            headers[header.key] = header.value
        }
        
        val body = if (httpRequest.content().readableBytes() > 0) {
            httpRequest.content().toString(CharsetUtil.UTF_8)
        } else null
        
        return ApiRequest(
            method = httpRequest.method().name(),
            path = path,
            headers = headers,
            body = body,
            queryParams = queryParams
        )
    }
    
    /**
     * 认证请求
     */
    private suspend fun authenticateRequest(
        request: ApiRequest,
        requestId: String
    ): GatewayContext {
        val authHeader = request.headers["Authorization"]
        val token = authHeader?.removePrefix("Bearer ")
        
        val userId = if (token != null) {
            val authToken = securityManager.validateToken(token)
            authToken?.userId
        } else null
        
        return GatewayContext(
            requestId = requestId,
            userId = userId,
            userToken = token
        )
    }
    
    /**
     * 检查限流
     */
    private fun checkRateLimit(userId: String, path: String): Boolean {
        val key = "$userId:$path"
        val rateLimiter = rateLimiters.computeIfAbsent(key) {
            RateLimiter(10, 20) // 默认限流：10 req/s，突发20
        }
        
        return rateLimiter.tryAcquire()
    }
    
    /**
     * 查找路由
     */
    private fun findRoute(method: String, path: String): Route? {
        return routes.find { route ->
            route.method.equals(method, ignoreCase = true) && 
            matchPath(route.path, path)
        }
    }
    
    /**
     * 路径匹配
     */
    private fun matchPath(routePath: String, requestPath: String): Boolean {
        // 简单的路径匹配，支持通配符
        if (routePath == requestPath) return true
        
        val routeParts = routePath.split("/")
        val requestParts = requestPath.split("/")
        
        if (routeParts.size != requestParts.size) return false
        
        return routeParts.zip(requestParts).all { (routePart, requestPart) ->
            routePart == requestPart || routePart.startsWith("{") && routePart.endsWith("}")
        }
    }
    
    /**
     * 发送响应
     */
    private fun sendResponse(ctx: ChannelHandlerContext, response: ApiResponse) {
        val httpResponse = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(response.status)
        )
        
        // 设置响应头
        response.headers.forEach { (key, value) ->
            httpResponse.headers().set(key, value)
        }
        
        // 设置响应体
        if (response.body != null) {
            val content = response.body.toByteArray(CharsetUtil.UTF_8)
            httpResponse.content().writeBytes(content)
            httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.size)
        }
        
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8")
        httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        
        ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE)
    }
    
    /**
     * 创建错误响应
     */
    private fun createErrorResponse(status: Int, message: String): ApiResponse {
        val errorBody = json.encodeToString(mapOf(
            "error" to message,
            "status" to status,
            "timestamp" to System.currentTimeMillis()
        ))
        
        return ApiResponse(
            status = status,
            body = errorBody
        )
    }
    
    private fun generateRequestId(): String {
        return "req-${System.currentTimeMillis()}-${requestIdGenerator.incrementAndGet()}"
    }
    
    /**
     * Netty处理器
     */
    private inner class ApiGatewayHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
        override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            CoroutineScope(Dispatchers.Default).launch {
                handleRequest(msg, ctx)
            }
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            cause.printStackTrace()
            ctx.close()
        }
    }
}

/**
 * 简单的限流器
 */
class RateLimiter(
    private val requestsPerSecond: Int,
    private val burstSize: Int
) {
    private var tokens = burstSize.toDouble()
    private var lastRefillTime = System.nanoTime()
    
    @Synchronized
    fun tryAcquire(): Boolean {
        refillTokens()
        
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }
    
    private fun refillTokens() {
        val now = System.nanoTime()
        val timePassed = (now - lastRefillTime) / 1_000_000_000.0
        val tokensToAdd = timePassed * requestsPerSecond
        
        tokens = minOf(burstSize.toDouble(), tokens + tokensToAdd)
        lastRefillTime = now
    }
}
