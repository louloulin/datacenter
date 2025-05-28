package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.consensus.RaftConsensus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 分布式锁服务
 * 基于 Raft 共识算法实现的分布式锁
 */
class DistributedLockService(
    private val raftConsensus: RaftConsensus,
    private val nodeId: String
) {
    private val locks = ConcurrentHashMap<String, LockInfo>()
    private val lockWaiters = ConcurrentHashMap<String, MutableList<LockWaiter>>()
    private val lockSequence = AtomicLong(0)
    private val mutex = Mutex()
    private val lockNotifications = ConcurrentHashMap<String, Channel<Boolean>>()
    
    /**
     * 尝试获取分布式锁
     */
    suspend fun tryLock(
        lockName: String,
        timeout: Duration = 30.seconds,
        leaseTime: Duration = 60.seconds
    ): DistributedLock? {
        val lockId = generateLockId()
        println("生成锁ID: $lockId")
        val request = LockRequest(
            lockId = lockId,
            lockName = lockName,
            nodeId = nodeId,
            timeout = timeout.inWholeMilliseconds,
            leaseTime = leaseTime.inWholeMilliseconds,
            timestamp = System.currentTimeMillis()
        )
        
        return try {
            // 先创建通知Channel
            val channel = Channel<Boolean>(1)
            lockNotifications[lockId] = channel
            println("创建通知Channel: $lockId")
            
            // 通过 Raft 共识提议锁请求
            println("提议锁请求: $request")
            val logIndex = raftConsensus.propose(serializeLockRequest(request))
            println("提议成功，日志索引: $logIndex")
            
            // 等待锁获取结果
            println("等待锁获取结果...")
            val result = waitForLockResult(lockId, timeout)
            println("锁获取结果: $result")
            result
        } catch (e: Exception) {
            println("锁获取异常: ${e.message}")
            e.printStackTrace()
            // 清理Channel
            lockNotifications.remove(lockId)
            null
        }
    }
    
    /**
     * 释放分布式锁
     */
    suspend fun unlock(lock: DistributedLock): Boolean {
        val request = UnlockRequest(
            lockId = lock.lockId,
            lockName = lock.lockName,
            nodeId = nodeId,
            timestamp = System.currentTimeMillis()
        )
        
        return try {
            // 通过 Raft 共识提议解锁请求
            raftConsensus.propose(serializeUnlockRequest(request))
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 处理锁操作日志条目
     */
    suspend fun applyLogEntry(data: ByteArray) {
        try {
            println("应用日志条目...")
            when (val operation = deserializeLockOperation(data)) {
                is LockRequest -> {
                    println("处理锁请求: $operation")
                    handleLockRequest(operation)
                }
                is UnlockRequest -> {
                    println("处理解锁请求: $operation")
                    handleUnlockRequest(operation)
                }
                is LockExpiration -> {
                    println("处理锁过期: $operation")
                    handleLockExpiration(operation)
                }
            }
        } catch (e: Exception) {
            println("日志条目格式错误: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 处理锁请求
     */
    private suspend fun handleLockRequest(request: LockRequest) {
        mutex.withLock {
            val existingLock = locks[request.lockName]
            println("处理锁请求 - 现有锁: $existingLock")
            
            if (existingLock == null || isLockExpired(existingLock)) {
                // 锁不存在或已过期，授予锁
                val lockInfo = LockInfo(
                    lockId = request.lockId,
                    lockName = request.lockName,
                    nodeId = request.nodeId,
                    acquiredTime = System.currentTimeMillis(),
                    leaseTime = request.leaseTime,
                    expirationTime = System.currentTimeMillis() + request.leaseTime
                )
                
                locks[request.lockName] = lockInfo
                println("授予锁: $lockInfo")
                
                // 通知等待者
                println("通知等待者: ${request.lockId}")
                notifyLockWaiter(request.lockId, true)
                
                // 设置锁过期定时器
                scheduleLockExpiration(lockInfo)
            } else {
                // 锁已被占用，加入等待队列
                val waiter = LockWaiter(
                    lockId = request.lockId,
                    nodeId = request.nodeId,
                    requestTime = System.currentTimeMillis(),
                    timeout = request.timeout
                )
                
                lockWaiters.computeIfAbsent(request.lockName) { mutableListOf() }.add(waiter)
                
                // 设置等待超时
                scheduleWaitTimeout(waiter, request.lockName)
            }
        }
    }
    
    /**
     * 处理解锁请求
     */
    private suspend fun handleUnlockRequest(request: UnlockRequest) {
        mutex.withLock {
            val existingLock = locks[request.lockName]
            
            if (existingLock != null && 
                existingLock.lockId == request.lockId && 
                existingLock.nodeId == request.nodeId) {
                
                // 移除锁
                locks.remove(request.lockName)
                
                // 处理等待队列
                val waiters = lockWaiters[request.lockName]
                if (!waiters.isNullOrEmpty()) {
                    // 查找下一个仍在等待的waiter（channel还存在）
                    var nextWaiter: LockWaiter? = null
                    while (waiters.isNotEmpty()) {
                        val candidate = waiters.removeAt(0)
                        if (lockNotifications.containsKey(candidate.lockId)) {
                            nextWaiter = candidate
                            break
                        }
                        // 如果channel不存在，说明已经超时，跳过这个waiter
                    }
                    
                    if (nextWaiter != null) {
                        // 为下一个等待者授予锁
                        val lockInfo = LockInfo(
                            lockId = nextWaiter.lockId,
                            lockName = request.lockName,
                            nodeId = nextWaiter.nodeId,
                            acquiredTime = System.currentTimeMillis(),
                            leaseTime = 60000, // 默认60秒
                            expirationTime = System.currentTimeMillis() + 60000
                        )
                        
                        locks[request.lockName] = lockInfo
                        notifyLockWaiter(nextWaiter.lockId, true)
                        scheduleLockExpiration(lockInfo)
                    }
                    
                    if (waiters.isEmpty()) {
                        lockWaiters.remove(request.lockName)
                    }
                }
            }
        }
    }
    
    /**
     * 处理锁过期
     */
    private suspend fun handleLockExpiration(expiration: LockExpiration) {
        mutex.withLock {
            val existingLock = locks[expiration.lockName]
            
            if (existingLock != null && existingLock.lockId == expiration.lockId) {
                // 移除过期的锁
                locks.remove(expiration.lockName)
                
                // 处理等待队列
                val waiters = lockWaiters[expiration.lockName]
                if (!waiters.isNullOrEmpty()) {
                    // 查找下一个仍在等待的waiter（channel还存在）
                    var nextWaiter: LockWaiter? = null
                    while (waiters.isNotEmpty()) {
                        val candidate = waiters.removeAt(0)
                        if (lockNotifications.containsKey(candidate.lockId)) {
                            nextWaiter = candidate
                            break
                        }
                        // 如果channel不存在，说明已经超时，跳过这个waiter
                    }
                    
                    if (nextWaiter != null) {
                        // 为下一个等待者授予锁
                        val lockInfo = LockInfo(
                            lockId = nextWaiter.lockId,
                            lockName = expiration.lockName,
                            nodeId = nextWaiter.nodeId,
                            acquiredTime = System.currentTimeMillis(),
                            leaseTime = 60000,
                            expirationTime = System.currentTimeMillis() + 60000
                        )
                        
                        locks[expiration.lockName] = lockInfo
                        notifyLockWaiter(nextWaiter.lockId, true)
                        scheduleLockExpiration(lockInfo)
                    }
                    
                    if (waiters.isEmpty()) {
                        lockWaiters.remove(expiration.lockName)
                    }
                }
            }
        }
    }
    
    /**
     * 等待锁获取结果
     */
    private suspend fun waitForLockResult(lockId: String, timeout: Duration): DistributedLock? {
        val channel = lockNotifications[lockId]
        if (channel == null) {
            println("waitForLockResult - 没有找到Channel: $lockId")
            return null
        }
        
        return try {
            withTimeoutOrNull(timeout) {
                println("waitForLockResult - 开始等待通知: $lockId")
                val success = channel.receive()
                println("waitForLockResult - 收到通知: $lockId, success: $success")
                if (success) {
                    val lock = findLockByLockId(lockId)
                    if (lock != null) {
                        DistributedLock(
                            lockId = lock.lockId,
                            lockName = lock.lockName,
                            nodeId = lock.nodeId,
                            acquiredTime = lock.acquiredTime,
                            expirationTime = lock.expirationTime
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } finally {
            // 清理Channel
            lockNotifications.remove(lockId)
            channel.close()
        }
    }
    
    /**
     * 查找锁信息
     */
    private fun findLockByLockId(lockId: String): LockInfo? {
        return locks.values.find { it.lockId == lockId }
    }
    
    /**
     * 检查锁是否过期
     */
    private fun isLockExpired(lockInfo: LockInfo): Boolean {
        return System.currentTimeMillis() > lockInfo.expirationTime
    }
    
    /**
     * 通知锁等待者
     */
    private fun notifyLockWaiter(lockId: String, success: Boolean) {
        println("notifyLockWaiter - lockId: $lockId, success: $success")
        val channel = lockNotifications[lockId]
        println("notifyLockWaiter - channel: $channel")
        if (channel != null) {
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    channel.send(success)
                    println("notifyLockWaiter - 已发送通知")
                } catch (e: Exception) {
                    // Channel可能已关闭
                }
            }
        } else {
            println("notifyLockWaiter - 没有找到等待的Channel")
        }
    }
    
    /**
     * 调度锁过期
     */
    private fun scheduleLockExpiration(lockInfo: LockInfo) {
        CoroutineScope(Dispatchers.Default).launch {
            delay(lockInfo.leaseTime)
            
            val expiration = LockExpiration(
                lockId = lockInfo.lockId,
                lockName = lockInfo.lockName,
                timestamp = System.currentTimeMillis()
            )
            
            try {
                raftConsensus.propose(serializeLockExpiration(expiration))
            } catch (e: Exception) {
                // 处理错误
            }
        }
    }
    
    /**
     * 调度等待超时
     */
    private fun scheduleWaitTimeout(waiter: LockWaiter, lockName: String) {
        CoroutineScope(Dispatchers.Default).launch {
            delay(waiter.timeout)
            
            mutex.withLock {
                val waiters = lockWaiters[lockName]
                waiters?.remove(waiter)
                if (waiters?.isEmpty() == true) {
                    lockWaiters.remove(lockName)
                }
                
                // 清理通知Channel
                lockNotifications.remove(waiter.lockId)
            }
            
            notifyLockWaiter(waiter.lockId, false)
        }
    }
    
    /**
     * 生成锁ID
     */
    private fun generateLockId(): String {
        return "${nodeId}_${lockSequence.incrementAndGet()}_${System.currentTimeMillis()}"
    }
    
    /**
     * 序列化锁请求
     */
    private fun serializeLockRequest(request: LockRequest): ByteArray {
        // 简化实现，实际应该使用更高效的序列化方式
        val data = "LOCK:${request.lockId}:${request.lockName}:${request.nodeId}:${request.timeout}:${request.leaseTime}:${request.timestamp}"
        return data.toByteArray()
    }
    
    /**
     * 序列化解锁请求
     */
    private fun serializeUnlockRequest(request: UnlockRequest): ByteArray {
        val data = "UNLOCK:${request.lockId}:${request.lockName}:${request.nodeId}:${request.timestamp}"
        return data.toByteArray()
    }
    
    /**
     * 序列化锁过期
     */
    private fun serializeLockExpiration(expiration: LockExpiration): ByteArray {
        val data = "EXPIRE:${expiration.lockId}:${expiration.lockName}:${expiration.timestamp}"
        return data.toByteArray()
    }
    
    /**
     * 反序列化锁操作
     */
    private fun deserializeLockOperation(data: ByteArray): LockOperation {
        val str = String(data)
        val parts = str.split(":")
        
        return when (parts[0]) {
            "LOCK" -> LockRequest(
                lockId = parts[1],
                lockName = parts[2],
                nodeId = parts[3],
                timeout = parts[4].toLong(),
                leaseTime = parts[5].toLong(),
                timestamp = parts[6].toLong()
            )
            "UNLOCK" -> UnlockRequest(
                lockId = parts[1],
                lockName = parts[2],
                nodeId = parts[3],
                timestamp = parts[4].toLong()
            )
            "EXPIRE" -> LockExpiration(
                lockId = parts[1],
                lockName = parts[2],
                timestamp = parts[3].toLong()
            )
            else -> throw IllegalArgumentException("Unknown lock operation: ${parts[0]}")
        }
    }
}

/**
 * 分布式锁
 */
data class DistributedLock(
    val lockId: String,
    val lockName: String,
    val nodeId: String,
    val acquiredTime: Long,
    val expirationTime: Long
)

/**
 * 锁信息
 */
data class LockInfo(
    val lockId: String,
    val lockName: String,
    val nodeId: String,
    val acquiredTime: Long,
    val leaseTime: Long,
    val expirationTime: Long
)

/**
 * 锁等待者
 */
data class LockWaiter(
    val lockId: String,
    val nodeId: String,
    val requestTime: Long,
    val timeout: Long
)

/**
 * 锁操作基类
 */
sealed class LockOperation

/**
 * 锁请求
 */
data class LockRequest(
    val lockId: String,
    val lockName: String,
    val nodeId: String,
    val timeout: Long,
    val leaseTime: Long,
    val timestamp: Long
) : LockOperation()

/**
 * 解锁请求
 */
data class UnlockRequest(
    val lockId: String,
    val lockName: String,
    val nodeId: String,
    val timestamp: Long
) : LockOperation()

/**
 * 锁过期
 */
data class LockExpiration(
    val lockId: String,
    val lockName: String,
    val timestamp: Long
) : LockOperation()