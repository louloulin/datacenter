package com.hftdc.disruptorx.util

import java.util.concurrent.ThreadFactory

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
    
    init {
        // Pre-populate the pool
        for (i in 0 until initialSize) {
            pool.add(factory())
        }
    }
    
    fun acquire(): T {
        return if (pool.isEmpty()) {
            factory()
        } else {
            pool.removeAt(pool.size - 1)
        }
    }
    
    fun release(obj: T) {
        pool.add(obj)
    }
} 