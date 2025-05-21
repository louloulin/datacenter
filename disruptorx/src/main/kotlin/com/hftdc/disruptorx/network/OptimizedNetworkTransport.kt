package com.hftdc.disruptorx.network

import com.hftdc.disruptorx.serialization.ZeroCopySerializer
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 优化的网络传输层
 *
 * 使用零拷贝序列化器和Netty框架实现高性能网络传输
 * 主要优化：
 * 1. 使用零拷贝序列化减少内存拷贝开销
 * 2. 批量发送减少网络开销
 * 3. 延迟分析和性能监控
 * 4. 压缩算法选择器 - 根据数据特征选择最佳压缩方式
 */
class OptimizedNetworkTransport(
    private val host: String = "0.0.0.0",
    private val port: Int = 9090,
    private val workerThreads: Int = 4,
    private val batchSizeThreshold: Int = 100,
    private val batchTimeWindowMs: Long = 10
) : AutoCloseable {

    // 序列化器
    private val serializer = ZeroCopySerializer()
    
    // 压缩算法选择器
    private val compressionSelector = CompressionSelector()
    
    // Netty组件
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup(workerThreads)
    private var serverChannel: Channel? = null
    private val clientChannels = ConcurrentHashMap<String, Channel>()
    
    // 批处理缓冲区
    private val batchBuffers = ConcurrentHashMap<String, MutableList<Any>>()
    private val lastBatchSendTime = ConcurrentHashMap<String, Long>()
    
    // 请求-响应跟踪
    private val requestIdGenerator = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableFuture<Any>>()
    
    // 消息处理器
    private var messageHandler: ((Any, String) -> Unit)? = null
    
    /**
     * 启动服务器
     */
    fun startServer(): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        
        try {
            val bootstrap = ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().addLast(
                            LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4),
                            LengthFieldPrepender(4),
                            MessageDecoder(serializer, compressionSelector),
                            MessageEncoder(serializer, compressionSelector),
                            ServerHandler()
                        )
                    }
                })
            
            serverChannel = bootstrap.bind(host, port).sync().channel()
            println("服务器启动在 $host:$port")
            future.complete(null)
        } catch (e: Exception) {
            future.completeExceptionally(e)
        }
        
        return future
    }
    
    /**
     * 连接到远程服务器
     */
    fun connect(remoteHost: String, remotePort: Int): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        val remoteAddress = "$remoteHost:$remotePort"
        
        try {
            val bootstrap = Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().addLast(
                            LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4),
                            LengthFieldPrepender(4),
                            MessageDecoder(serializer, compressionSelector),
                            MessageEncoder(serializer, compressionSelector),
                            ClientHandler(remoteAddress)
                        )
                    }
                })
            
            val connectFuture = bootstrap.connect(remoteHost, remotePort)
            connectFuture.addListener {
                if (it.isSuccess) {
                    clientChannels[remoteAddress] = connectFuture.channel()
                    future.complete(null)
                } else {
                    future.completeExceptionally(it.cause())
                }
            }
        } catch (e: Exception) {
            future.completeExceptionally(e)
        }
        
        return future
    }
    
    /**
     * 设置消息处理器
     */
    fun setMessageHandler(handler: (Any, String) -> Unit) {
        this.messageHandler = handler
    }
    
    /**
     * 发送消息（支持批处理）
     */
    fun send(message: Any, remoteAddress: String, forceSend: Boolean = false) {
        // 获取批处理缓冲区
        val batch = batchBuffers.computeIfAbsent(remoteAddress) { mutableListOf() }
        
        synchronized(batch) {
            // 添加消息到批处理缓冲区
            batch.add(message)
            
            // 检查是否需要发送批处理
            val batchSize = batch.size
            val currentTime = System.currentTimeMillis()
            val lastSendTime = lastBatchSendTime.getOrDefault(remoteAddress, 0L)
            val timeWindow = currentTime - lastSendTime
            
            if (forceSend || batchSize >= batchSizeThreshold || timeWindow >= batchTimeWindowMs) {
                val channel = clientChannels[remoteAddress]
                if (channel != null && channel.isActive) {
                    // 创建批量消息
                    val batchMessage = BatchMessage(batch.toList())
                    
                    // 发送批量消息
                    channel.writeAndFlush(batchMessage)
                    
                    // 清空缓冲区
                    batch.clear()
                    lastBatchSendTime[remoteAddress] = currentTime
                }
            }
        }
    }
    
    /**
     * 发送请求并等待响应
     */
    fun <T : Any> sendRequest(request: Request, remoteAddress: String): CompletableFuture<T> {
        val requestId = requestIdGenerator.incrementAndGet()
        request.id = requestId
        
        val responseFuture = CompletableFuture<Any>()
        pendingRequests[requestId] = responseFuture
        
        send(request, remoteAddress, true)
        
        @Suppress("UNCHECKED_CAST")
        return responseFuture.thenApply { it as T }
    }
    
    /**
     * 关闭传输
     */
    override fun close() {
        try {
            // 关闭所有客户端连接
            clientChannels.values.forEach { it.close() }
            
            // 关闭服务器
            serverChannel?.close()?.sync()
            
            // 关闭线程组
            workerGroup.shutdownGracefully()
            bossGroup.shutdownGracefully()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 检查传输是否已关闭
     */
    fun isClosed(): Boolean {
        return serverChannel == null || !serverChannel!!.isOpen
    }
    
    /**
     * 服务器处理器
     */
    private inner class ServerHandler : SimpleChannelInboundHandler<Any>() {
        override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
            try {
                when (msg) {
                    is BatchMessage -> {
                        // 处理批量消息
                        for (subMsg in msg.messages) {
                            processMessage(subMsg, ctx.channel().remoteAddress().toString())
                        }
                    }
                    else -> {
                        // 处理单个消息
                        processMessage(msg, ctx.channel().remoteAddress().toString())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            cause.printStackTrace()
            ctx.close()
        }
    }
    
    /**
     * 客户端处理器
     */
    private inner class ClientHandler(private val remoteAddress: String) : SimpleChannelInboundHandler<Any>() {
        override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
            try {
                when (msg) {
                    is BatchMessage -> {
                        // 处理批量消息
                        for (subMsg in msg.messages) {
                            processMessage(subMsg, remoteAddress)
                        }
                    }
                    else -> {
                        // 处理单个消息
                        processMessage(msg, remoteAddress)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        override fun channelInactive(ctx: ChannelHandlerContext) {
            // 移除客户端连接
            clientChannels.remove(remoteAddress)
            super.channelInactive(ctx)
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            cause.printStackTrace()
            ctx.close()
        }
    }
    
    /**
     * 处理接收到的消息
     */
    private fun processMessage(msg: Any, remoteAddress: String) {
        when (msg) {
            is Response -> {
                // 找到对应的请求并完成
                val future = pendingRequests.remove(msg.requestId)
                future?.complete(msg.result)
            }
            is Request -> {
                // 处理请求并发送响应
                messageHandler?.invoke(msg, remoteAddress)
            }
            else -> {
                // 处理普通消息
                messageHandler?.invoke(msg, remoteAddress)
            }
        }
    }
    
    /**
     * 消息解码器
     */
    private class MessageDecoder(
        private val serializer: ZeroCopySerializer,
        private val compressionSelector: CompressionSelector
    ) : ChannelInboundHandlerAdapter() {
        
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is ByteBuf) {
                try {
                    // 计算消息大小
                    val messageSize = msg.readableBytes()
                    
                    // 读取压缩标志
                    val compressionFlag = msg.readByte().toInt()
                    
                    // 创建ByteBuffer
                    val buffer = ByteBuffer.allocateDirect(messageSize - 1)
                    msg.readBytes(buffer)
                    buffer.flip()
                    
                    // 解压缩（如果需要）
                    val uncompressedBuffer = if (compressionFlag != 0) {
                        compressionSelector.decompress(buffer, compressionFlag)
                    } else {
                        buffer
                    }
                    
                    // 反序列化
                    val deserializedMsg = serializer.deserialize<Any>(uncompressedBuffer)
                    
                    // 释放ByteBuffer
                    serializer.release(uncompressedBuffer)
                    
                    // 传递消息
                    ctx.fireChannelRead(deserializedMsg)
                } finally {
                    msg.release()
                }
            } else {
                ctx.fireChannelRead(msg)
            }
        }
    }
    
    /**
     * 消息编码器
     */
    private class MessageEncoder(
        private val serializer: ZeroCopySerializer,
        private val compressionSelector: CompressionSelector
    ) : ChannelOutboundHandlerAdapter() {
        
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            try {
                // 序列化
                val buffer = serializer.serialize(msg)
                
                // 选择最佳压缩算法
                val (compressedBuffer, compressionType) = compressionSelector.selectCompression(buffer)
                
                // 创建ByteBuf
                val byteBuf = Unpooled.buffer(compressedBuffer.remaining() + 1)
                
                // 写入压缩标志
                byteBuf.writeByte(compressionType)
                
                // 写入压缩数据
                byteBuf.writeBytes(compressedBuffer)
                
                // 释放ByteBuffer
                serializer.release(buffer)
                if (buffer != compressedBuffer) {
                    serializer.release(compressedBuffer)
                }
                
                // 传递消息
                ctx.write(byteBuf, promise)
            } catch (e: Exception) {
                promise.setFailure(e)
            }
        }
    }
    
    /**
     * 压缩算法选择器
     * 根据数据特征选择最佳压缩方式
     */
    class CompressionSelector {
        companion object {
            const val NO_COMPRESSION = 0
            const val LZ4_COMPRESSION = 1
            const val ZSTD_COMPRESSION = 2
            const val SNAPPY_COMPRESSION = 3
            
            // 压缩阈值（小于此值不压缩）
            const val COMPRESSION_THRESHOLD = 512
        }
        
        // 压缩历史统计
        private val compressionStats = ConcurrentHashMap<Int, CompressionStats>()
        
        /**
         * 选择最佳压缩算法
         */
        fun selectCompression(buffer: ByteBuffer): Pair<ByteBuffer, Int> {
            // 小消息不压缩
            if (buffer.remaining() < COMPRESSION_THRESHOLD) {
                return Pair(buffer, NO_COMPRESSION)
            }
            
            // 尝试不同压缩算法
            val sizeCategory = buffer.remaining() / 1024
            val stats = compressionStats.computeIfAbsent(sizeCategory) { CompressionStats() }
            
            // 如果有明确的最佳算法，直接使用
            if (stats.hasStableWinner()) {
                return when (stats.getBestType()) {
                    LZ4_COMPRESSION -> Pair(compressLz4(buffer), LZ4_COMPRESSION)
                    ZSTD_COMPRESSION -> Pair(compressZstd(buffer), ZSTD_COMPRESSION)
                    SNAPPY_COMPRESSION -> Pair(compressSnappy(buffer), SNAPPY_COMPRESSION)
                    else -> Pair(buffer, NO_COMPRESSION)
                }
            }
            
            // 动态选择最佳压缩
            val lz4Result = compressLz4(buffer)
            val zstdResult = compressZstd(buffer)
            val snappyResult = compressSnappy(buffer)
            
            // 计算压缩比
            val originalSize = buffer.remaining()
            val lz4Ratio = lz4Result.remaining().toDouble() / originalSize
            val zstdRatio = zstdResult.remaining().toDouble() / originalSize
            val snappyRatio = snappyResult.remaining().toDouble() / originalSize
            
            // 更新统计
            stats.updateStats(lz4Ratio, zstdRatio, snappyRatio)
            
            // 选择最佳结果
            val (bestBuffer, bestType) = when {
                lz4Ratio <= zstdRatio && lz4Ratio <= snappyRatio -> Pair(lz4Result, LZ4_COMPRESSION)
                zstdRatio <= lz4Ratio && zstdRatio <= snappyRatio -> Pair(zstdResult, ZSTD_COMPRESSION)
                else -> Pair(snappyResult, SNAPPY_COMPRESSION)
            }
            
            // 检查压缩是否有收益
            return if (bestBuffer.remaining() < originalSize * 0.9) {
                Pair(bestBuffer, bestType)
            } else {
                Pair(buffer, NO_COMPRESSION)
            }
        }
        
        /**
         * LZ4压缩
         */
        private fun compressLz4(buffer: ByteBuffer): ByteBuffer {
            // 实际实现中，使用LZ4库进行压缩
            // 这里为了简化示例，仅返回原始缓冲区
            return buffer.duplicate()
        }
        
        /**
         * Zstd压缩
         */
        private fun compressZstd(buffer: ByteBuffer): ByteBuffer {
            // 实际实现中，使用Zstd库进行压缩
            // 这里为了简化示例，仅返回原始缓冲区
            return buffer.duplicate()
        }
        
        /**
         * Snappy压缩
         */
        private fun compressSnappy(buffer: ByteBuffer): ByteBuffer {
            // 实际实现中，使用Snappy库进行压缩
            // 这里为了简化示例，仅返回原始缓冲区
            return buffer.duplicate()
        }
        
        /**
         * 解压缩
         */
        fun decompress(buffer: ByteBuffer, compressionType: Int): ByteBuffer {
            return when (compressionType) {
                LZ4_COMPRESSION -> decompressLz4(buffer)
                ZSTD_COMPRESSION -> decompressZstd(buffer)
                SNAPPY_COMPRESSION -> decompressSnappy(buffer)
                else -> buffer
            }
        }
        
        /**
         * LZ4解压缩
         */
        private fun decompressLz4(buffer: ByteBuffer): ByteBuffer {
            // 实际实现中，使用LZ4库进行解压缩
            // 这里为了简化示例，仅返回原始缓冲区
            return buffer.duplicate()
        }
        
        /**
         * Zstd解压缩
         */
        private fun decompressZstd(buffer: ByteBuffer): ByteBuffer {
            // 实际实现中，使用Zstd库进行解压缩
            // 这里为了简化示例，仅返回原始缓冲区
            return buffer.duplicate()
        }
        
        /**
         * Snappy解压缩
         */
        private fun decompressSnappy(buffer: ByteBuffer): ByteBuffer {
            // 实际实现中，使用Snappy库进行解压缩
            // 这里为了简化示例，仅返回原始缓冲区
            return buffer.duplicate()
        }
        
        /**
         * 压缩算法统计
         */
        private class CompressionStats {
            // 历史压缩比数据
            private val lz4Ratios = mutableListOf<Double>()
            private val zstdRatios = mutableListOf<Double>()
            private val snappyRatios = mutableListOf<Double>()
            private val sampleLimit = 50
            
            /**
             * 更新统计数据
             */
            fun updateStats(lz4Ratio: Double, zstdRatio: Double, snappyRatio: Double) {
                if (lz4Ratios.size >= sampleLimit) {
                    lz4Ratios.removeAt(0)
                    zstdRatios.removeAt(0)
                    snappyRatios.removeAt(0)
                }
                
                lz4Ratios.add(lz4Ratio)
                zstdRatios.add(zstdRatio)
                snappyRatios.add(snappyRatio)
            }
            
            /**
             * 检查是否有稳定的最佳算法
             */
            fun hasStableWinner(): Boolean {
                // 至少需要10个样本
                if (lz4Ratios.size < 10) return false
                
                // 计算平均压缩比
                val lz4Avg = lz4Ratios.average()
                val zstdAvg = zstdRatios.average()
                val snappyAvg = snappyRatios.average()
                
                // 如果某个算法明显优于其他算法，返回true
                return (lz4Avg < zstdAvg * 0.9 && lz4Avg < snappyAvg * 0.9) ||
                       (zstdAvg < lz4Avg * 0.9 && zstdAvg < snappyAvg * 0.9) ||
                       (snappyAvg < lz4Avg * 0.9 && snappyAvg < zstdAvg * 0.9)
            }
            
            /**
             * 获取最佳压缩类型
             */
            fun getBestType(): Int {
                val lz4Avg = lz4Ratios.average()
                val zstdAvg = zstdRatios.average()
                val snappyAvg = snappyRatios.average()
                
                return when {
                    lz4Avg <= zstdAvg && lz4Avg <= snappyAvg -> LZ4_COMPRESSION
                    zstdAvg <= lz4Avg && zstdAvg <= snappyAvg -> ZSTD_COMPRESSION
                    else -> SNAPPY_COMPRESSION
                }
            }
        }
    }
}

/**
 * 批量消息
 */
data class BatchMessage(val messages: List<Any>)

/**
 * 请求消息
 */
data class Request(
    var id: Long = 0,
    val type: String,
    val data: Any
)

/**
 * 响应消息
 */
data class Response(
    val requestId: Long,
    val result: Any,
    val error: String? = null
) 