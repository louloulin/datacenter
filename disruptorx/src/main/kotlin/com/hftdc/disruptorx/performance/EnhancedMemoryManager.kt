package com.hftdc.disruptorx.performance

import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 增强内存管理器
 * 提供多种内存优化策略和监控功能
 */
class EnhancedMemoryManager(
    private val enableDirectMemory: Boolean = true,
    private val enableMemoryMapping: Boolean = true,
    private val cleanupInterval: Duration = 30.seconds
) {
    
    // ByteBuffer池 - 不同大小的缓冲区
    private val smallBufferPool = BufferPool(1024, 200, enableDirectMemory)      // 1KB
    private val mediumBufferPool = BufferPool(64 * 1024, 100, enableDirectMemory) // 64KB
    private val largeBufferPool = BufferPool(1024 * 1024, 50, enableDirectMemory) // 1MB
    
    // 对象池
    private val stringBuilderPool = ObjectPool({ StringBuilder(256) }, { it.clear() }, 100)
    private val byteArrayPool = ObjectPool({ ByteArray(4096) }, {}, 50)
    private val intArrayPool = ObjectPool({ IntArray(1024) }, {}, 30)
    private val longArrayPool = ObjectPool({ LongArray(512) }, {}, 30)
    
    // 统计信息
    private val totalAllocations = AtomicLong(0)
    private val totalDeallocations = AtomicLong(0)
    private val poolHits = AtomicLong(0)
    private val poolMisses = AtomicLong(0)
    
    // 清理任务
    private var cleanupJob: Job? = null
    
    init {
        startCleanupTask()
        warmupPools()
    }
    
    /**
     * 获取ByteBuffer
     */
    fun acquireByteBuffer(size: Int): ByteBuffer {
        totalAllocations.incrementAndGet()
        
        val buffer = when {
            size <= 1024 -> smallBufferPool.acquire()
            size <= 64 * 1024 -> mediumBufferPool.acquire()
            size <= 1024 * 1024 -> largeBufferPool.acquire()
            else -> null
        }
        
        return if (buffer != null) {
            poolHits.incrementAndGet()
            buffer
        } else {
            poolMisses.incrementAndGet()
            if (enableDirectMemory) {
                ByteBuffer.allocateDirect(size)
            } else {
                ByteBuffer.allocate(size)
            }
        }
    }
    
    /**
     * 释放ByteBuffer
     */
    fun releaseByteBuffer(buffer: ByteBuffer) {
        totalDeallocations.incrementAndGet()
        
        when (buffer.capacity()) {
            1024 -> smallBufferPool.release(buffer)
            64 * 1024 -> mediumBufferPool.release(buffer)
            1024 * 1024 -> largeBufferPool.release(buffer)
            // 其他大小的缓冲区直接丢弃
        }
    }
    
    /**
     * 获取StringBuilder
     */
    fun acquireStringBuilder(): StringBuilder {
        totalAllocations.incrementAndGet()
        return stringBuilderPool.acquire() ?: StringBuilder(256)
    }
    
    /**
     * 释放StringBuilder
     */
    fun releaseStringBuilder(sb: StringBuilder) {
        totalDeallocations.incrementAndGet()
        stringBuilderPool.release(sb)
    }
    
    /**
     * 获取ByteArray
     */
    fun acquireByteArray(size: Int = 4096): ByteArray {
        totalAllocations.incrementAndGet()
        return if (size == 4096) {
            byteArrayPool.acquire() ?: ByteArray(size)
        } else {
            ByteArray(size)
        }
    }
    
    /**
     * 释放ByteArray
     */
    fun releaseByteArray(array: ByteArray) {
        totalDeallocations.incrementAndGet()
        if (array.size == 4096) {
            byteArrayPool.release(array)
        }
    }
    
    /**
     * 获取IntArray
     */
    fun acquireIntArray(size: Int = 1024): IntArray {
        totalAllocations.incrementAndGet()
        return if (size == 1024) {
            intArrayPool.acquire() ?: IntArray(size)
        } else {
            IntArray(size)
        }
    }
    
    /**
     * 释放IntArray
     */
    fun releaseIntArray(array: IntArray) {
        totalDeallocations.incrementAndGet()
        if (array.size == 1024) {
            intArrayPool.release(array)
        }
    }
    
    /**
     * 获取LongArray
     */
    fun acquireLongArray(size: Int = 512): LongArray {
        totalAllocations.incrementAndGet()
        return if (size == 512) {
            longArrayPool.acquire() ?: LongArray(size)
        } else {
            LongArray(size)
        }
    }
    
    /**
     * 释放LongArray
     */
    fun releaseLongArray(array: LongArray) {
        totalDeallocations.incrementAndGet()
        if (array.size == 512) {
            longArrayPool.release(array)
        }
    }
    
    /**
     * 获取内存统计信息
     */
    fun getMemoryStats(): MemoryStats {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()
        
        val totalRequests = poolHits.get() + poolMisses.get()
        val hitRate = if (totalRequests > 0) poolHits.get().toDouble() / totalRequests else 0.0
        
        return MemoryStats(
            totalMemory = totalMemory,
            usedMemory = usedMemory,
            freeMemory = freeMemory,
            maxMemory = maxMemory,
            poolHitRate = hitRate,
            totalAllocations = totalAllocations.get(),
            totalDeallocations = totalDeallocations.get(),
            smallBufferPoolSize = smallBufferPool.size(),
            mediumBufferPoolSize = mediumBufferPool.size(),
            largeBufferPoolSize = largeBufferPool.size()
        )
    }
    
    /**
     * 强制垃圾回收
     */
    fun forceGC() {
        System.gc()
        System.runFinalization()
    }
    
    /**
     * 清理所有池
     */
    fun cleanup() {
        smallBufferPool.clear()
        mediumBufferPool.clear()
        largeBufferPool.clear()
        stringBuilderPool.clear()
        byteArrayPool.clear()
        intArrayPool.clear()
        longArrayPool.clear()
        
        cleanupJob?.cancel()
    }
    
    /**
     * 预热池
     */
    private fun warmupPools() {
        // 预分配一些对象来触发JIT编译和池初始化
        repeat(50) {
            val smallBuffer = acquireByteBuffer(1024)
            releaseByteBuffer(smallBuffer)
            
            val mediumBuffer = acquireByteBuffer(32 * 1024)
            releaseByteBuffer(mediumBuffer)
            
            val sb = acquireStringBuilder()
            sb.append("warmup")
            releaseStringBuilder(sb)
            
            val byteArray = acquireByteArray()
            releaseByteArray(byteArray)
        }
    }
    
    /**
     * 启动清理任务
     */
    private fun startCleanupTask() {
        cleanupJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(cleanupInterval)
                
                val runtime = Runtime.getRuntime()
                val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                val memoryUsageRatio = usedMemory.toDouble() / runtime.maxMemory()
                
                // 如果内存使用率超过80%，进行清理
                if (memoryUsageRatio > 0.8) {
                    cleanupPools()
                    forceGC()
                }
            }
        }
    }
    
    /**
     * 清理池中的部分对象
     */
    private fun cleanupPools() {
        // 清理一半的池对象
        repeat(smallBufferPool.size() / 2) {
            smallBufferPool.acquire()?.let { /* 让它被GC */ }
        }
        
        repeat(mediumBufferPool.size() / 2) {
            mediumBufferPool.acquire()?.let { /* 让它被GC */ }
        }
        
        repeat(largeBufferPool.size() / 2) {
            largeBufferPool.acquire()?.let { /* 让它被GC */ }
        }
    }
}

/**
 * 缓冲区池
 */
private class BufferPool(
    private val bufferSize: Int,
    private val maxPoolSize: Int,
    private val isDirect: Boolean
) {
    private val pool = ConcurrentLinkedQueue<ByteBuffer>()
    private val currentSize = AtomicInteger(0)
    
    fun acquire(): ByteBuffer? {
        val buffer = pool.poll()
        if (buffer != null) {
            currentSize.decrementAndGet()
            buffer.clear()
            return buffer
        }
        return null
    }
    
    fun release(buffer: ByteBuffer) {
        if (currentSize.get() < maxPoolSize && buffer.capacity() == bufferSize) {
            buffer.clear()
            pool.offer(buffer)
            currentSize.incrementAndGet()
        }
    }
    
    fun size(): Int = currentSize.get()
    
    fun clear() {
        pool.clear()
        currentSize.set(0)
    }
}

/**
 * 通用对象池
 */
private class ObjectPool<T>(
    private val factory: () -> T,
    private val reset: (T) -> Unit,
    private val maxSize: Int
) {
    private val pool = ConcurrentLinkedQueue<T>()
    private val currentSize = AtomicInteger(0)
    
    fun acquire(): T? {
        val obj = pool.poll()
        if (obj != null) {
            currentSize.decrementAndGet()
            return obj
        }
        return null
    }
    
    fun release(obj: T) {
        if (currentSize.get() < maxSize) {
            reset(obj)
            pool.offer(obj)
            currentSize.incrementAndGet()
        }
    }
    
    fun size(): Int = currentSize.get()
    
    fun clear() {
        pool.clear()
        currentSize.set(0)
    }
}

/**
 * 内存统计信息
 */
data class MemoryStats(
    val totalMemory: Long,
    val usedMemory: Long,
    val freeMemory: Long,
    val maxMemory: Long,
    val poolHitRate: Double,
    val totalAllocations: Long,
    val totalDeallocations: Long,
    val smallBufferPoolSize: Int,
    val mediumBufferPoolSize: Int,
    val largeBufferPoolSize: Int
)

/**
 * 便捷使用函数
 */
inline fun <T> EnhancedMemoryManager.useByteBuffer(size: Int, block: (ByteBuffer) -> T): T {
    val buffer = acquireByteBuffer(size)
    return try {
        block(buffer)
    } finally {
        releaseByteBuffer(buffer)
    }
}

inline fun <T> EnhancedMemoryManager.useStringBuilder(block: (StringBuilder) -> T): T {
    val sb = acquireStringBuilder()
    return try {
        block(sb)
    } finally {
        releaseStringBuilder(sb)
    }
}
