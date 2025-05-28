package com.hftdc.disruptorx.consensus

import com.hftdc.disruptorx.api.NodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Raft 共识算法实现
 * 提供分布式环境下的强一致性保证
 */
class RaftConsensus(
    private val nodeId: String,
    private val clusterNodes: List<NodeInfo>
) {
    // 状态机应用器
    private var stateMachineApplier: (suspend (ByteArray) -> Unit)? = null
    // Raft 状态
    private val currentTerm = AtomicLong(0)
    private val votedFor = AtomicReference<String?>(null)
    private val log = mutableListOf<LogEntry>()
    private val commitIndex = AtomicLong(0)
    private val lastApplied = AtomicLong(-1)
    
    // Leader 状态
    private val nextIndex = ConcurrentHashMap<String, Long>()
    private val matchIndex = ConcurrentHashMap<String, Long>()
    
    // 节点状态
    @Volatile
    private var state = RaftState.FOLLOWER
    @Volatile
    private var leaderId: String? = null
    
    // 定时器
    private var electionTimeout: Job? = null
    private var heartbeatJob: Job? = null
    
    // 通信通道
    private val messageChannel = Channel<RaftMessage>(Channel.UNLIMITED)
    
    // 锁
    private val stateMutex = Mutex()
    
    /**
     * 设置状态机应用器
     */
    fun setStateMachineApplier(applier: suspend (ByteArray) -> Unit) {
        this.stateMachineApplier = applier
    }
    
    /**
     * 启动 Raft 节点
     */
    suspend fun start() {
        println("启动 Raft 节点: $nodeId")
        becomeFollower()
        
        // 启动消息处理协程
        CoroutineScope(Dispatchers.IO).launch {
            processMessages()
        }
    }
    
    /**
     * 获取当前任期
     */
    fun getCurrentTerm(): Long {
        return currentTerm.get()
    }
    
    /**
     * 检查是否为Leader
     */
    fun isLeader(): Boolean {
        return state == RaftState.LEADER
    }
    
    /**
     * 提议新的日志条目
     */
    suspend fun propose(data: ByteArray): Long {
        println("propose - 当前状态: $state")
        if (state != RaftState.LEADER) {
            throw IllegalStateException("Only leader can propose entries")
        }
        
        stateMutex.withLock {
            val entry = LogEntry(
                term = currentTerm.get(),
                index = log.size.toLong(),
                data = data,
                timestamp = System.currentTimeMillis()
            )
            
            println("propose - 创建日志条目: $entry")
            log.add(entry)
            println("propose - 日志大小: ${log.size}")
            
            // 复制到大多数节点
            println("propose - 开始复制到大多数节点")
            val replicationResult = replicateToMajority(entry)
            println("propose - 复制结果: $replicationResult")
            
            if (replicationResult) {
                println("propose - 设置commitIndex: ${entry.index}")
                commitIndex.set(entry.index)
                println("propose - 当前commitIndex: ${commitIndex.get()}")
                // 立即应用已提交的日志条目（简化实现）
                println("propose - 开始应用已提交的日志条目")
                applyCommittedEntries()
                return entry.index
            } else {
                // 回滚
                println("propose - 复制失败，回滚")
                log.removeAt(log.size - 1)
                throw IllegalStateException("Failed to replicate to majority")
            }
        }
    }
    
    /**
     * 处理投票请求
     */
    suspend fun handleVoteRequest(request: VoteRequest): VoteResponse {
        stateMutex.withLock {
            val currentTermValue = currentTerm.get()
            
            // 如果请求的任期更大，更新当前任期
            if (request.term > currentTermValue) {
                currentTerm.set(request.term)
                votedFor.set(null)
                becomeFollower()
            }
            
            val grantVote = when {
                request.term < currentTermValue -> false
                votedFor.get() != null && votedFor.get() != request.candidateId -> false
                !isLogUpToDate(request.lastLogIndex, request.lastLogTerm) -> false
                else -> {
                    votedFor.set(request.candidateId)
                    resetElectionTimeout()
                    true
                }
            }
            
            return VoteResponse(
                term = currentTerm.get(),
                voteGranted = grantVote
            )
        }
    }
    
    /**
     * 处理追加条目请求
     */
    suspend fun handleAppendEntries(request: AppendEntriesRequest): AppendEntriesResponse {
        stateMutex.withLock {
            val currentTermValue = currentTerm.get()
            
            // 如果请求的任期更大，更新当前任期
            if (request.term > currentTermValue) {
                currentTerm.set(request.term)
                votedFor.set(null)
                becomeFollower()
            }
            
            // 拒绝过期的请求
            if (request.term < currentTermValue) {
                return AppendEntriesResponse(
                    term = currentTermValue,
                    success = false
                )
            }
            
            // 重置选举超时
            resetElectionTimeout()
            leaderId = request.leaderId
            
            // 检查日志一致性
            if (request.prevLogIndex > 0) {
                if (log.size <= request.prevLogIndex.toInt() ||
                    log[request.prevLogIndex.toInt()].term != request.prevLogTerm) {
                    return AppendEntriesResponse(
                        term = currentTermValue,
                        success = false
                    )
                }
            }
            
            // 追加新条目
            if (request.entries.isNotEmpty()) {
                // 删除冲突的条目
                val startIndex = request.prevLogIndex + 1
                if (log.size > startIndex.toInt()) {
                    log.subList(startIndex.toInt(), log.size).clear()
                }
                
                // 添加新条目
                log.addAll(request.entries)
            }
            
            // 更新提交索引
            if (request.leaderCommit > commitIndex.get()) {
                commitIndex.set(minOf(request.leaderCommit, log.size.toLong() - 1))
            }
            
            return AppendEntriesResponse(
                term = currentTermValue,
                success = true
            )
        }
    }
    
    /**
     * 开始选举
     */
    private suspend fun startElection() {
        stateMutex.withLock {
            state = RaftState.CANDIDATE
            currentTerm.incrementAndGet()
            votedFor.set(nodeId)
            resetElectionTimeout()
            
            val votes = AtomicLong(1) // 自己的票
            val majority = (clusterNodes.size + 1) / 2 + 1
            
            // 向所有其他节点发送投票请求
            clusterNodes.filter { it.nodeId != nodeId }.forEach { node ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val request = VoteRequest(
                            term = currentTerm.get(),
                            candidateId = nodeId,
                            lastLogIndex = log.size.toLong() - 1,
                            lastLogTerm = if (log.isNotEmpty()) log.last().term else 0
                        )
                        
                        val response = sendVoteRequest(node.nodeId, request)
                        
                        if (response?.voteGranted == true) {
                            val currentVotes = votes.incrementAndGet()
                            if (currentVotes >= majority && state == RaftState.CANDIDATE) {
                                becomeLeader()
                            }
                        } else if (response?.term ?: 0 > currentTerm.get()) {
                            currentTerm.set(response?.term ?: 0)
                            votedFor.set(null)
                            becomeFollower()
                        }
                    } catch (e: Exception) {
                        // 网络错误，忽略这个节点的投票
                    }
                }
            }
        }
    }
    
    /**
     * 成为 Leader
     */
    suspend fun becomeLeader() {
        state = RaftState.LEADER
        leaderId = nodeId
        
        // 初始化 Leader 状态
        clusterNodes.forEach { node ->
            nextIndex[node.nodeId] = log.size.toLong()
            matchIndex[node.nodeId] = 0
        }
        
        // 开始发送心跳
        startHeartbeat()
    }
    
    /**
     * 成为 Follower
     */
    private suspend fun becomeFollower() {
        state = RaftState.FOLLOWER
        heartbeatJob?.cancel()
        resetElectionTimeout()
    }
    
    /**
     * 开始心跳
     */
    private fun startHeartbeat() {
        heartbeatJob = CoroutineScope(Dispatchers.Default).launch {
            while (state == RaftState.LEADER) {
                sendHeartbeat()
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }
    
    /**
     * 发送心跳
     */
    private suspend fun sendHeartbeat() {
        clusterNodes.filter { it.nodeId != nodeId }.forEach { node ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val request = AppendEntriesRequest(
                        term = currentTerm.get(),
                        leaderId = nodeId,
                        prevLogIndex = 0,
                        prevLogTerm = 0,
                        entries = emptyList(),
                        leaderCommit = commitIndex.get()
                    )
                    
                    sendAppendEntries(node.nodeId, request)
                } catch (e: Exception) {
                    // 网络错误，忽略
                }
            }
        }
    }
    
    /**
     * 复制到大多数节点
     */
    private suspend fun replicateToMajority(entry: LogEntry): Boolean {
        // 对于单节点集群，直接返回成功
        if (clusterNodes.size == 1) {
            return true
        }
        
        val majority = (clusterNodes.size + 1) / 2
        val successCount = AtomicLong(1) // 包括自己
        
        val jobs = clusterNodes.filter { it.nodeId != nodeId }.map { node ->
            CoroutineScope(Dispatchers.IO).async {
                try {
                    val request = AppendEntriesRequest(
                        term = currentTerm.get(),
                        leaderId = nodeId,
                        prevLogIndex = entry.index - 1,
                        prevLogTerm = if (entry.index > 0) log[(entry.index - 1).toInt()].term else 0,
                        entries = listOf(entry),
                        leaderCommit = commitIndex.get()
                    )
                    
                    val response = sendAppendEntries(node.nodeId, request)
                    
                    if (response?.success == true) {
                        successCount.incrementAndGet()
                    }
                } catch (e: Exception) {
                    // 网络错误
                }
            }
        }
        
        // 等待所有请求完成
        jobs.awaitAll()
        
        return successCount.get() >= majority
    }
    
    /**
     * 重置选举超时
     */
    private fun resetElectionTimeout() {
        electionTimeout?.cancel()
        electionTimeout = CoroutineScope(Dispatchers.Default).launch {
            delay(Random.nextLong(ELECTION_TIMEOUT_MIN, ELECTION_TIMEOUT_MAX))
            if (state != RaftState.LEADER) {
                startElection()
            }
        }
    }
    
    /**
     * 检查日志是否最新
     */
    private fun isLogUpToDate(lastLogIndex: Long, lastLogTerm: Long): Boolean {
        if (log.isEmpty()) return true
        
        val ourLastEntry = log.last()
        return when {
            lastLogTerm > ourLastEntry.term -> true
            lastLogTerm < ourLastEntry.term -> false
            else -> lastLogIndex >= log.size - 1
        }
    }
    
    /**
     * 处理消息
     */
    private suspend fun processMessages() {
        for (message in messageChannel) {
            when (message) {
                is VoteRequestMessage -> {
                    val response = handleVoteRequest(message.request)
                    // 发送响应给发送者
                }
                is AppendEntriesMessage -> {
                    val response = handleAppendEntries(message.request)
                    // 发送响应给发送者
                }
            }
        }
    }
    
    /**
     * 处理单个消息
     */
    private suspend fun handleMessage(message: RaftMessage) {
        when (message) {
            is VoteRequestMessage -> {
                val response = handleVoteRequest(message.request)
                // 发送响应给发送者
            }
            is AppendEntriesMessage -> {
                val response = handleAppendEntries(message.request)
                // 发送响应给发送者
            }
        }
    }
    
    // HTTP服务器
    private var httpServer: RaftHttpServer? = null
    
    /**
     * 启动Raft节点和HTTP服务器
     */
    suspend fun startWithHttpServer(port: Int) {
        httpServer = RaftHttpServer(port, this)
        httpServer?.start()
        start()
    }
    
    /**
     * 停止Raft节点和HTTP服务器
     */
    fun stopWithHttpServer() {
        httpServer?.stop()
        // 停止相关服务
    }
    
    /**
     * 发送投票请求到指定节点
     */
    private suspend fun sendVoteRequest(nodeId: String, request: VoteRequest): VoteResponse? {
        return try {
            val parts = nodeId.split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toInt() else 8080
            val requestJson = serializeVoteRequest(request)
            val responseJson = sendHttpRequest(host, port, "/raft/vote", "POST", requestJson)
            responseJson?.let { deserializeVoteResponse(it) }
        } catch (e: Exception) {
            println("发送投票请求到 $nodeId 失败: ${e.message}")
            null
        }
    }
    
    /**
     * 发送日志复制请求到指定节点
     */
    private suspend fun sendAppendEntries(nodeId: String, request: AppendEntriesRequest): AppendEntriesResponse? {
        return try {
            val parts = nodeId.split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toInt() else 8080
            val requestJson = serializeAppendEntriesRequest(request)
            val responseJson = sendHttpRequest(host, port, "/raft/append", "POST", requestJson)
            responseJson?.let { deserializeAppendEntriesResponse(it) }
        } catch (e: Exception) {
            println("发送日志复制请求到 $nodeId 失败: ${e.message}")
            null
        }
    }
    

    
    /**
     * 简单的HTTP客户端实现
     */
    private suspend fun sendHttpRequest(
        host: String,
        port: Int,
        path: String,
        method: String,
        body: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val url = java.net.URL("http://$host:$port$path")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = method
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            // 发送请求体
            connection.outputStream.use { os ->
                os.write(body.toByteArray())
            }
            
            // 读取响应
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                throw RuntimeException("HTTP错误: $responseCode")
            }
        } catch (e: Exception) {
            throw RuntimeException("网络请求失败: ${e.message}", e)
        }
    }
    
    /**
     * 序列化投票请求
     */
    private fun serializeVoteRequest(request: VoteRequest): String {
        return """{
"term":${request.term},
"candidateId":"${request.candidateId}",
"lastLogIndex":${request.lastLogIndex},
"lastLogTerm":${request.lastLogTerm}
}"""
    }
    
    /**
     * 反序列化投票响应
     */
    private fun deserializeVoteResponse(json: String): VoteResponse {
        // 简单的JSON解析
        val termMatch = """"term":(\d+)""".toRegex().find(json)
        val voteGrantedMatch = """"voteGranted":(true|false)""".toRegex().find(json)
        
        return VoteResponse(
            term = termMatch?.groupValues?.get(1)?.toLong() ?: 0,
            voteGranted = voteGrantedMatch?.groupValues?.get(1)?.toBoolean() ?: false
        )
    }
    
    /**
     * 序列化日志复制请求
     */
    private fun serializeAppendEntriesRequest(request: AppendEntriesRequest): String {
        val entriesJson = request.entries.joinToString(",") { entry ->
            """{
"term":${entry.term},
"index":${entry.index},
"data":"${java.util.Base64.getEncoder().encodeToString(entry.data)}",
"timestamp":${entry.timestamp}
}"""
        }
        return """{
"term":${request.term},
"leaderId":"${request.leaderId}",
"prevLogIndex":${request.prevLogIndex},
"prevLogTerm":${request.prevLogTerm},
"entries":[$entriesJson],
"leaderCommit":${request.leaderCommit}
}"""
    }
    
    /**
     * 反序列化日志复制响应
     */
    private fun deserializeAppendEntriesResponse(json: String): AppendEntriesResponse {
        // 简单的JSON解析
        val termMatch = """"term":(\d+)""".toRegex().find(json)
        val successMatch = """"success":(true|false)""".toRegex().find(json)
        
        return AppendEntriesResponse(
            term = termMatch?.groupValues?.get(1)?.toLong() ?: 0,
            success = successMatch?.groupValues?.get(1)?.toBoolean() ?: false
        )
    }

    /**
     * 应用已提交的日志条目
     */
    private suspend fun applyCommittedEntries() {
        val currentLastApplied = lastApplied.get()
        val currentCommitIndex = commitIndex.get()
        
        println("应用已提交的日志条目 - lastApplied: $currentLastApplied, commitIndex: $currentCommitIndex")
        
        // 修复：当lastApplied为-1时，从0开始应用
        val startIndex = if (currentLastApplied == -1L) 0L else currentLastApplied + 1
        
        for (index in startIndex..currentCommitIndex) {
            if (index < log.size) {
                val entry = log[index.toInt()]
                println("应用日志条目 - index: $index, entry: $entry")
                println("stateMachineApplier: $stateMachineApplier")
                stateMachineApplier?.invoke(entry.data)
                lastApplied.set(index)
                println("已应用日志条目 - index: $index")
            } else {
                println("跳过日志条目 - index: $index, log.size: ${log.size}")
            }
        }
    }
    
    companion object {
        private const val HEARTBEAT_INTERVAL = 50L // 50ms
        private const val ELECTION_TIMEOUT_MIN = 150L // 150ms
        private const val ELECTION_TIMEOUT_MAX = 300L // 300ms
    }
}

/**
 * Raft 状态枚举
 */
enum class RaftState {
    FOLLOWER,
    CANDIDATE,
    LEADER
}

/**
 * 日志条目
 */
data class LogEntry(
    val term: Long,
    val index: Long,
    val data: ByteArray,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as LogEntry
        
        if (term != other.term) return false
        if (index != other.index) return false
        if (!data.contentEquals(other.data)) return false
        if (timestamp != other.timestamp) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = term.hashCode()
        result = 31 * result + index.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * 投票请求
 */
data class VoteRequest(
    val term: Long,
    val candidateId: String,
    val lastLogIndex: Long,
    val lastLogTerm: Long
)

/**
 * 投票响应
 */
data class VoteResponse(
    val term: Long,
    val voteGranted: Boolean
)

/**
 * 追加条目请求
 */
data class AppendEntriesRequest(
    val term: Long,
    val leaderId: String,
    val prevLogIndex: Long,
    val prevLogTerm: Long,
    val entries: List<LogEntry>,
    val leaderCommit: Long
)

/**
 * 追加条目响应
 */
data class AppendEntriesResponse(
    val term: Long,
    val success: Boolean
)

/**
 * Raft 消息基类
 */
sealed class RaftMessage

data class VoteRequestMessage(
    val request: VoteRequest,
    val senderId: String
) : RaftMessage()

data class AppendEntriesMessage(
    val request: AppendEntriesRequest,
    val senderId: String
) : RaftMessage()