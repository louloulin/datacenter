package com.hftdc.disruptorx.performance

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 事件对象池
 * 用于重用事件对象，减少GC压力
 *
 * @param T 对象类型
 * @param initialSize 初始池大小
 * @param maxSize 最大池大小
 * @param factory 对象工厂函数
 */
class EventObjectPool<T : Any>(
    initialSize: Int,
    private val maxSize: Int,
    private val factory: () -> T
) {
    // 对象存储池
    private val pool = ConcurrentLinkedQueue<T>()
    
    // 池大小计数器
    private val size = AtomicInteger(0)
    
    // 统计信息
    private val created = AtomicInteger(0)
    private val reused = AtomicInteger(0)
    private val returned = AtomicInteger(0)
    
    init {
        // 初始化对象池
        repeat(initialSize) {
            val obj = factory()
            pool.offer(obj)
            size.incrementAndGet()
            created.incrementAndGet()
        }
    }
    
    /**
     * 从池中获取一个对象，如果池为空则创建新对象
     * @return 池中对象或新创建的对象
     */
    fun acquire(): T {
        val pooledObject = pool.poll()
        return if (pooledObject != null) {
            size.decrementAndGet()
            reused.incrementAndGet()
            pooledObject
        } else {
            created.incrementAndGet()
            factory()
        }
    }
    
    /**
     * 将对象返回池中
     * @param obj 要返回的对象
     * @return 是否成功返回到池中
     */
    fun release(obj: T): Boolean {
        returned.incrementAndGet()
        if (size.get() < maxSize) {
            pool.offer(obj)
            size.incrementAndGet()
            return true
        }
        return false
    }
    
    /**
     * 获取当前池大小
     * @return 当前池中的对象数量
     */
    fun currentSize(): Int = size.get()
    
    /**
     * 获取创建的对象总数
     * @return 创建的对象总数
     */
    fun getCreatedCount(): Int = created.get()
    
    /**
     * 获取重用的对象总数
     * @return 重用的对象总数
     */
    fun getReusedCount(): Int = reused.get()
    
    /**
     * 获取返回池的对象总数
     * @return 返回池的对象总数
     */
    fun getReturnedCount(): Int = returned.get()
    
    /**
     * 清空对象池
     */
    fun clear() {
        pool.clear()
        size.set(0)
    }
    
    /**
     * 池执行器，自动管理对象的获取和返回
     * @param action 使用池对象执行的操作
     * @return 操作结果
     */
    fun <R> usePooled(action: (T) -> R): R {
        val obj = acquire()
        try {
            return action(obj)
        } finally {
            release(obj)
        }
    }
} 