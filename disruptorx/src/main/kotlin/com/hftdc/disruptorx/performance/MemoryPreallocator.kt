package com.hftdc.disruptorx.performance

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 内存预分配器
 * 预先分配内存资源并进行管理，减少运行时分配开销
 */
class MemoryPreallocator<T : Any>(
    private val initialCapacity: Int,
    private val maxCapacity: Int,
    private val expandFactor: Double = 1.5,
    private val factory: () -> T
) {
    // 预分配的内存资源
    private val resources = ArrayList<T>(initialCapacity)
    
    // 当前可用资源索引
    private val availableIndex = AtomicInteger(0)
    
    // 统计信息
    private val allocatedCount = AtomicInteger(0)
    private val expandCount = AtomicInteger(0)
    private val recycleCount = AtomicInteger(0)
    
    // 资源标记，用于跟踪已分配的资源
    private val allocatedResources = ConcurrentHashMap<T, Boolean>()
    
    // 锁用于保护扩容操作
    private val expandLock = ReentrantLock()
    
    init {
        // 初始化资源池
        repeat(initialCapacity) {
            resources.add(factory())
        }
        allocatedCount.set(0)
    }
    
    /**
     * 分配一个资源
     * @return 预分配的资源
     */
    fun allocate(): T {
        // 尝试从预分配池获取资源
        val index = availableIndex.getAndIncrement()
        
        if (index < resources.size) {
            val resource = resources[index]
            allocatedResources[resource] = true
            allocatedCount.incrementAndGet()
            return resource
        }
        
        // 需要扩容
        return expandLock.withLock {
            // 再次检查，防止多线程情况下重复扩容
            val currentIndex = availableIndex.get() - 1
            if (currentIndex < resources.size) {
                // 另一个线程已经完成了扩容
                availableIndex.decrementAndGet() // 回退索引
                return allocate() // 重试分配
            }
            
            // 确认需要扩容
            val currentSize = resources.size
            val newSize = (currentSize * expandFactor).toInt()
            val expandSize = if (newSize > maxCapacity) maxCapacity - currentSize else newSize - currentSize
            
            if (expandSize <= 0) {
                // 已达到最大容量，创建一个新资源但不添加到池中
                val resource = factory()
                allocatedResources[resource] = true
                allocatedCount.incrementAndGet()
                return resource
            }
            
            // 扩容资源池
            repeat(expandSize) {
                resources.add(factory())
            }
            
            expandCount.incrementAndGet()
            
            // 分配资源
            val resource = resources[currentIndex]
            allocatedResources[resource] = true
            allocatedCount.incrementAndGet()
            return resource
        }
    }
    
    /**
     * 回收资源
     * @param resource 要回收的资源
     * @return 是否成功回收
     */
    fun recycle(resource: T): Boolean {
        // 检查资源是否由此分配器分配
        if (!allocatedResources.remove(resource)) {
            return false
        }
        
        recycleCount.incrementAndGet()
        allocatedCount.decrementAndGet()
        
        // 当回收量达到阈值时，重置可用索引
        val currentAvailable = availableIndex.get()
        val currentAllocated = allocatedCount.get()
        
        if (currentAvailable > resources.size / 2 && currentAllocated < resources.size / 4) {
            // 尝试重置索引，允许重用前面的资源
            availableIndex.compareAndSet(currentAvailable, 0)
        }
        
        return true
    }
    
    /**
     * 获取当前已分配的资源数量
     * @return 已分配资源数
     */
    fun getAllocatedCount(): Int {
        return allocatedCount.get()
    }
    
    /**
     * 获取资源池大小
     * @return 资源池大小
     */
    fun getCapacity(): Int {
        return resources.size
    }
    
    /**
     * 获取扩容次数
     * @return 扩容次数
     */
    fun getExpandCount(): Int {
        return expandCount.get()
    }
    
    /**
     * 获取回收次数
     * @return 回收次数
     */
    fun getRecycleCount(): Int {
        return recycleCount.get()
    }
    
    /**
     * 使用预分配资源执行操作，并自动回收
     * @param action 使用资源执行的操作
     * @return 操作结果
     */
    fun <R> use(action: (T) -> R): R {
        val resource = allocate()
        try {
            return action(resource)
        } finally {
            recycle(resource)
        }
    }
    
    /**
     * 清空资源池
     */
    fun clear() {
        expandLock.withLock {
            resources.clear()
            allocatedResources.clear()
            availableIndex.set(0)
            allocatedCount.set(0)
            
            // 重新初始化
            repeat(initialCapacity) {
                resources.add(factory())
            }
        }
    }
} 