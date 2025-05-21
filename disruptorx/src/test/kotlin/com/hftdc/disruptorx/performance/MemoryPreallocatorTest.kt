package com.hftdc.disruptorx.performance

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MemoryPreallocatorTest {
    
    private lateinit var preallocator: MemoryPreallocator<TestResource>
    private val initialCapacity = 100
    private val maxCapacity = 1000
    private var factoryCallCount = AtomicInteger(0)
    
    class TestResource {
        val id = AtomicInteger(0)
        var data = ByteArray(1024) // 1KB
        
        fun init(newId: Int): TestResource {
            id.set(newId)
            return this
        }
    }
    
    @BeforeEach
    fun setUp() {
        factoryCallCount.set(0)
        preallocator = MemoryPreallocator(
            initialCapacity = initialCapacity,
            maxCapacity = maxCapacity,
            factory = {
                factoryCallCount.incrementAndGet()
                TestResource()
            }
        )
    }
    
    @Test
    fun `verify initialization`() {
        assertEquals(initialCapacity, factoryCallCount.get(), "Factory should be called exactly initialCapacity times")
        assertEquals(initialCapacity, preallocator.getCapacity(), "Initial capacity should match")
        assertEquals(0, preallocator.getAllocatedCount(), "No resources should be allocated yet")
    }
    
    @Test
    fun `allocate and recycle single resource`() {
        // 分配资源
        val resource = preallocator.allocate()
        assertNotNull(resource, "Allocated resource should not be null")
        assertEquals(1, preallocator.getAllocatedCount(), "One resource should be allocated")
        
        // 回收资源
        val recycled = preallocator.recycle(resource)
        assertTrue(recycled, "Resource should be recycled successfully")
        assertEquals(0, preallocator.getAllocatedCount(), "No resources should be allocated after recycle")
        assertEquals(1, preallocator.getRecycleCount(), "Recycle count should be incremented")
    }
    
    @Test
    fun `use function properly handles resources`() {
        val result = preallocator.use { resource ->
            resource.init(42)
            "Success: ${resource.id.get()}"
        }
        
        assertEquals("Success: 42", result, "Use function should return correct result")
        assertEquals(0, preallocator.getAllocatedCount(), "No resources should be allocated after use")
        assertEquals(1, preallocator.getRecycleCount(), "Recycle count should be incremented")
    }
    
    @Test
    fun `expands when capacity is reached`() {
        // 分配所有初始资源
        val resources = List(initialCapacity) { preallocator.allocate() }
        assertEquals(initialCapacity, preallocator.getAllocatedCount(), "All initial resources should be allocated")
        
        // 分配一个额外资源触发扩容
        val extraResource = preallocator.allocate()
        assertTrue(preallocator.getCapacity() > initialCapacity, "Capacity should increase after expansion")
        assertEquals(1, preallocator.getExpandCount(), "Expand count should be incremented")
        
        // 回收所有资源
        resources.forEach { preallocator.recycle(it) }
        preallocator.recycle(extraResource)
        assertEquals(0, preallocator.getAllocatedCount(), "All resources should be recycled")
    }
    
    @Test
    fun `respects maximum capacity`() {
        // 创建小容量预分配器
        val smallPreallocator = MemoryPreallocator<TestResource>(
            initialCapacity = 10,
            maxCapacity = 20,
            factory = { TestResource() }
        )
        
        // 分配超过最大容量的资源
        val resources = List(30) { smallPreallocator.allocate() }
        
        // 验证容量上限
        assertEquals(20, smallPreallocator.getCapacity(), "Capacity should not exceed maximum")
        assertEquals(30, smallPreallocator.getAllocatedCount(), "All requested resources should be allocated")
        
        // 回收资源
        resources.forEach { smallPreallocator.recycle(it) }
    }
    
    @Test
    fun `concurrent allocation and recycling`() {
        val threadCount = 10
        val resourcesPerThread = 100
        val totalResources = threadCount * resourcesPerThread
        
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val completionLatch = CountDownLatch(threadCount)
        
        // 并发分配和回收
        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    // 等待所有线程准备就绪
                    startLatch.await()
                    
                    repeat(resourcesPerThread) {
                        preallocator.use { resource ->
                            // 模拟使用资源
                            resource.init(i * resourcesPerThread + it)
                            Thread.sleep(1) // 小延迟增加并发性
                        }
                    }
                } finally {
                    completionLatch.countDown()
                }
            }
        }
        
        // 启动所有线程
        startLatch.countDown()
        
        // 等待完成
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS), "All threads should complete in 30 seconds")
        
        // 验证结果
        assertEquals(0, preallocator.getAllocatedCount(), "No resources should remain allocated")
        assertEquals(totalResources, preallocator.getRecycleCount(), "All resources should be recycled")
        
        // 关闭线程池
        executor.shutdown()
    }
    
    @Test
    fun `clear resets preallocator state`() {
        // 分配一些资源
        val resources = List(50) { preallocator.allocate() }
        assertEquals(50, preallocator.getAllocatedCount(), "Resources should be allocated")
        
        // 清空预分配器
        preallocator.clear()
        assertEquals(initialCapacity, preallocator.getCapacity(), "Capacity should reset to initial value")
        assertEquals(0, preallocator.getAllocatedCount(), "No resources should be allocated after clear")
        
        // 验证可以继续使用
        val newResource = preallocator.allocate()
        assertNotNull(newResource, "Should be able to allocate after clear")
        preallocator.recycle(newResource)
    }
    
    @Test
    fun `index resets when many resources are recycled`() {
        // 分配大量资源
        val threshold = initialCapacity / 2 + 1
        val resources = List(threshold) { preallocator.allocate() }
        
        // 回收大部分资源触发索引重置
        for (i in 0 until threshold - 5) {
            preallocator.recycle(resources[i])
        }
        
        // 分配新资源并验证是否重用了前面的槽位
        val availableBeforeReset = preallocator.use { resource ->
            // 检查是否是新分配的资源或重用的资源
            resources.any { it === resource }
        }
        
        assertTrue(availableBeforeReset, "Should reuse resources after index reset")
    }
} 