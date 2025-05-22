package com.hftdc.disruptorx.util

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread affinity utility for tests
 * Stub implementation to make tests compile
 */
object ThreadAffinity {
    fun allocateCoresForCriticalProcessing(coreCount: Int): List<Int> {
        // Stub implementation that returns an empty list of cores
        return emptyList()
    }
    
    fun newThreadFactory(namePrefix: String, cores: List<Int> = emptyList()): ThreadFactory {
        return ThreadFactory { r ->
            val thread = Thread(r)
            thread.name = "$namePrefix-${thread.id}"
            thread
        }
    }
    
    fun newFixedThreadPool(threadCount: Int, namePrefix: String): java.util.concurrent.ExecutorService {
        return java.util.concurrent.Executors.newFixedThreadPool(threadCount, 
            newThreadFactory(namePrefix))
    }
}

/**
 * Event object pool for tests
 * Stub implementation to make tests compile
 */
class EventObjectPool<T>(
    initialSize: Int,
    maxSize: Int,
    private val factory: () -> T
) {
    private val pool = ArrayList<T>()
    private val createdCount = AtomicInteger(0)
    private val reusedCount = AtomicInteger(0)
    
    init {
        // Pre-populate the pool
        for (i in 0 until initialSize) {
            pool.add(factory())
            createdCount.incrementAndGet()
        }
    }
    
    fun acquire(): T {
        return if (pool.isEmpty()) {
            createdCount.incrementAndGet()
            factory()
        } else {
            reusedCount.incrementAndGet()
            pool.removeAt(pool.size - 1)
        }
    }
    
    fun release(obj: T) {
        pool.add(obj)
    }
    
    fun getCreatedCount(): Int = createdCount.get()
    
    fun getReusedCount(): Int = reusedCount.get()
}

/**
 * PaddedSequence for tests
 * Stub implementation to make tests compile
 */
class PaddedSequence(private var value: Long) {
    fun set(newValue: Long) {
        value = newValue
    }
    
    fun get(): Long = value
}

/**
 * OffHeapBuffer for tests
 * Stub implementation to make tests compile
 */
class OffHeapBuffer private constructor(private val size: Int) : AutoCloseable {
    
    fun write(bytes: ByteArray): Long {
        // Stub implementation
        return 0
    }
    
    fun reset() {
        // Stub implementation
    }
    
    override fun close() {
        // Stub implementation
    }
    
    companion object {
        fun allocate(size: Int): OffHeapBuffer {
            return OffHeapBuffer(size)
        }
    }
} 