package com.hftdc.disruptorx.integration

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Distributed lock representation
 */
data class DistributedLock(
    val lockName: String,
    val nodeId: String,
    val acquiredAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 30000L // 30 seconds default
)

/**
 * Distributed lock service for tests
 */
class DistributedLockService(private val nodeId: String) {
    private val locks = ConcurrentHashMap<String, DistributedLock>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    @Volatile
    private var isRunning = false
    
    fun initialize() {
        isRunning = true
        startLockCleanup()
    }
    
    fun shutdown() {
        isRunning = false
        scope.cancel()
        locks.clear()
    }
    
    /**
     * Try to acquire a distributed lock
     */
    suspend fun tryLock(lockName: String, timeout: Duration = 5.seconds): DistributedLock? {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        
        while (System.currentTimeMillis() < deadline) {
            val existingLock = locks[lockName]
            
            // Check if lock is expired
            if (existingLock != null && existingLock.expiresAt < System.currentTimeMillis()) {
                locks.remove(lockName, existingLock)
            }
            
            // Try to acquire lock
            val newLock = DistributedLock(
                lockName = lockName,
                nodeId = nodeId,
                acquiredAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 30000L
            )
            
            if (locks.putIfAbsent(lockName, newLock) == null) {
                return newLock
            }
            
            // Wait a bit before retrying
            delay(10)
        }
        
        return null
    }
    
    /**
     * Release a distributed lock
     */
    fun unlock(lock: DistributedLock): Boolean {
        val currentLock = locks[lock.lockName]
        
        // Only allow the lock owner to release it
        if (currentLock != null && currentLock.nodeId == lock.nodeId) {
            return locks.remove(lock.lockName, currentLock)
        }
        
        return false
    }
    
    /**
     * Check if a lock is currently held
     */
    fun isLocked(lockName: String): Boolean {
        val lock = locks[lockName] ?: return false
        
        // Check if lock is expired
        if (lock.expiresAt < System.currentTimeMillis()) {
            locks.remove(lockName, lock)
            return false
        }
        
        return true
    }
    
    /**
     * Get information about a lock
     */
    fun getLockInfo(lockName: String): DistributedLock? {
        val lock = locks[lockName] ?: return null
        
        // Check if lock is expired
        if (lock.expiresAt < System.currentTimeMillis()) {
            locks.remove(lockName, lock)
            return null
        }
        
        return lock
    }
    
    /**
     * Get all active locks
     */
    fun getActiveLocks(): List<DistributedLock> {
        val currentTime = System.currentTimeMillis()
        return locks.values.filter { it.expiresAt > currentTime }
    }
    
    /**
     * Extend lock expiration time
     */
    fun extendLock(lock: DistributedLock, additionalTime: Duration = 30.seconds): Boolean {
        val currentLock = locks[lock.lockName]
        
        if (currentLock != null && currentLock.nodeId == lock.nodeId) {
            val extendedLock = currentLock.copy(
                expiresAt = currentLock.expiresAt + additionalTime.inWholeMilliseconds
            )
            return locks.replace(lock.lockName, currentLock, extendedLock)
        }
        
        return false
    }
    
    /**
     * Force release a lock (for cleanup purposes)
     */
    fun forceUnlock(lockName: String): Boolean {
        return locks.remove(lockName) != null
    }
    
    /**
     * Start background cleanup of expired locks
     */
    private fun startLockCleanup() {
        scope.launch {
            while (isRunning) {
                try {
                    cleanupExpiredLocks()
                    delay(5000) // Cleanup every 5 seconds
                } catch (e: Exception) {
                    // Log error but continue
                    if (isRunning) {
                        println("Error in lock cleanup: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Remove expired locks
     */
    private fun cleanupExpiredLocks() {
        val currentTime = System.currentTimeMillis()
        val expiredLocks = locks.values.filter { it.expiresAt < currentTime }
        
        expiredLocks.forEach { lock ->
            locks.remove(lock.lockName, lock)
        }
        
        if (expiredLocks.isNotEmpty()) {
            println("Cleaned up ${expiredLocks.size} expired locks")
        }
    }
    
    /**
     * Get lock statistics
     */
    fun getLockStats(): LockStats {
        val currentTime = System.currentTimeMillis()
        val activeLocks = locks.values.filter { it.expiresAt > currentTime }
        val expiredLocks = locks.values.filter { it.expiresAt <= currentTime }
        
        return LockStats(
            totalLocks = locks.size,
            activeLocks = activeLocks.size,
            expiredLocks = expiredLocks.size,
            locksByNode = activeLocks.groupBy { it.nodeId }.mapValues { it.value.size }
        )
    }
}

/**
 * Lock statistics
 */
data class LockStats(
    val totalLocks: Int,
    val activeLocks: Int,
    val expiredLocks: Int,
    val locksByNode: Map<String, Int>
)
