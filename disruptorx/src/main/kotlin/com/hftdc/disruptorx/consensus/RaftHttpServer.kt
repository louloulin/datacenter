package com.hftdc.disruptorx.consensus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Raft HTTP 服务器
 * 处理来自其他节点的 Raft 协议请求
 */
class RaftHttpServer(
    private val port: Int,
    private val raftConsensus: RaftConsensus
) {
    private var server: HttpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * 启动HTTP服务器
     */
    suspend fun start() {
        withContext(Dispatchers.IO) {
            server = HttpServer.create(InetSocketAddress(port), 0)
            
            // 注册Raft协议处理器
            server?.createContext("/raft/vote", VoteRequestHandler())
            server?.createContext("/raft/append", AppendEntriesHandler())
            server?.createContext("/health", HealthHandler())
            
            // 使用线程池处理请求
            server?.executor = Executors.newFixedThreadPool(4)
            server?.start()
            
            println("Raft HTTP服务器启动在端口: $port")
        }
    }
    
    /**
     * 停止HTTP服务器
     */
    fun stop() {
        server?.stop(0)
        scope.cancel()
        println("Raft HTTP服务器已停止")
    }
    
    /**
     * 投票请求处理器
     */
    private inner class VoteRequestHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    if (exchange.requestMethod == "POST") {
                        val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                        val voteRequest = deserializeVoteRequest(requestBody)
                        
                        // 处理投票请求
                        val response = raftConsensus.handleVoteRequest(voteRequest)
                        val responseJson = serializeVoteResponse(response)
                        
                        // 发送响应
                        exchange.responseHeaders.set("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, responseJson.length.toLong())
                        exchange.responseBody.use { os ->
                            os.write(responseJson.toByteArray())
                        }
                    } else {
                        exchange.sendResponseHeaders(405, 0) // Method Not Allowed
                    }
                } catch (e: Exception) {
                    println("处理投票请求失败: ${e.message}")
                    exchange.sendResponseHeaders(500, 0)
                } finally {
                    exchange.close()
                }
            }
        }
    }
    
    /**
     * 日志复制请求处理器
     */
    private inner class AppendEntriesHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    if (exchange.requestMethod == "POST") {
                        val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                        val appendRequest = deserializeAppendEntriesRequest(requestBody)
                        
                        // 处理日志复制请求
                        val response = raftConsensus.handleAppendEntries(appendRequest)
                        val responseJson = serializeAppendEntriesResponse(response)
                        
                        // 发送响应
                        exchange.responseHeaders.set("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, responseJson.length.toLong())
                        exchange.responseBody.use { os ->
                            os.write(responseJson.toByteArray())
                        }
                    } else {
                        exchange.sendResponseHeaders(405, 0) // Method Not Allowed
                    }
                } catch (e: Exception) {
                    println("处理日志复制请求失败: ${e.message}")
                    exchange.sendResponseHeaders(500, 0)
                } finally {
                    exchange.close()
                }
            }
        }
    }
    
    /**
     * 健康检查处理器
     */
    private inner class HealthHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                val response = """{"status":"ok","term":${raftConsensus.getCurrentTerm()},"isLeader":${raftConsensus.isLeader()}}"""
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.use { os ->
                    os.write(response.toByteArray())
                }
            } catch (e: Exception) {
                exchange.sendResponseHeaders(500, 0)
            } finally {
                exchange.close()
            }
        }
    }
    
    /**
     * 反序列化投票请求
     */
    private fun deserializeVoteRequest(json: String): VoteRequest {
        val termMatch = """"term":(\d+)""".toRegex().find(json)
        val candidateIdMatch = """"candidateId":"([^"]+)"""".toRegex().find(json)
        val lastLogIndexMatch = """"lastLogIndex":(\d+)""".toRegex().find(json)
        val lastLogTermMatch = """"lastLogTerm":(\d+)""".toRegex().find(json)
        
        return VoteRequest(
            term = termMatch?.groupValues?.get(1)?.toLong() ?: 0,
            candidateId = candidateIdMatch?.groupValues?.get(1) ?: "",
            lastLogIndex = lastLogIndexMatch?.groupValues?.get(1)?.toLong() ?: 0,
            lastLogTerm = lastLogTermMatch?.groupValues?.get(1)?.toLong() ?: 0
        )
    }
    
    /**
     * 序列化投票响应
     */
    private fun serializeVoteResponse(response: VoteResponse): String {
        return """{"term":${response.term},"voteGranted":${response.voteGranted}}"""
    }
    
    /**
     * 反序列化日志复制请求
     */
    private fun deserializeAppendEntriesRequest(json: String): AppendEntriesRequest {
        val termMatch = """"term":(\d+)""".toRegex().find(json)
        val leaderIdMatch = """"leaderId":"([^"]+)"""".toRegex().find(json)
        val prevLogIndexMatch = """"prevLogIndex":(\d+)""".toRegex().find(json)
        val prevLogTermMatch = """"prevLogTerm":(\d+)""".toRegex().find(json)
        val leaderCommitMatch = """"leaderCommit":(\d+)""".toRegex().find(json)
        
        // 解析日志条目数组
        val entriesPattern = """"entries":\[([^\]]*)]""".toRegex()
        val entriesMatch = entriesPattern.find(json)
        val entries = mutableListOf<LogEntry>()
        
        entriesMatch?.groupValues?.get(1)?.let { entriesJson ->
            if (entriesJson.isNotBlank()) {
                // 简单解析每个日志条目
                val entryPattern = """\{[^}]+\}""".toRegex()
                entryPattern.findAll(entriesJson).forEach { entryMatch: MatchResult ->
                    val entryJson = entryMatch.value
                    val entryTermMatch = """"term":(\d+)""".toRegex().find(entryJson)
                    val entryIndexMatch = """"index":(\d+)""".toRegex().find(entryJson)
                    val entryDataMatch = """"data":"([^"]+)"""".toRegex().find(entryJson)
                    val entryTimestampMatch = """"timestamp":(\d+)""".toRegex().find(entryJson)
                    
                    if (entryTermMatch != null && entryIndexMatch != null && entryDataMatch != null && entryTimestampMatch != null) {
                        val data = java.util.Base64.getDecoder().decode(entryDataMatch.groupValues[1])
                        entries.add(
                            LogEntry(
                                term = entryTermMatch.groupValues[1].toLong(),
                                index = entryIndexMatch.groupValues[1].toLong(),
                                data = data,
                                timestamp = entryTimestampMatch.groupValues[1].toLong()
                            )
                        )
                    }
                }
            }
        }
        
        return AppendEntriesRequest(
            term = termMatch?.groupValues?.get(1)?.toLong() ?: 0,
            leaderId = leaderIdMatch?.groupValues?.get(1) ?: "",
            prevLogIndex = prevLogIndexMatch?.groupValues?.get(1)?.toLong() ?: 0,
            prevLogTerm = prevLogTermMatch?.groupValues?.get(1)?.toLong() ?: 0,
            entries = entries,
            leaderCommit = leaderCommitMatch?.groupValues?.get(1)?.toLong() ?: 0
        )
    }
    
    /**
     * 序列化日志复制响应
     */
    private fun serializeAppendEntriesResponse(response: AppendEntriesResponse): String {
        return """{"term":${response.term},"success":${response.success}}"""
    }
}