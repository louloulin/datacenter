package com.hftdc.disruptorx.performance

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class EnhancedMemoryManagerTest {

    private lateinit var memoryManager: EnhancedMemoryManager

    @BeforeEach
    fun setup() {
        memoryManager = EnhancedMemoryManager()
    }

    @Test
    fun `test acquire and release byte buffer`() {
        val buffer = memoryManager.acquireByteBuffer(1024)
        
        assertNotNull(buffer)
        assertEquals(1024, buffer.capacity())
        assertTrue(buffer.isDirect) // 默认使用直接内存
        
        memoryManager.releaseByteBuffer(buffer)
        
        // 验证统计信息
        val stats = memoryManager.getMemoryStats()
        assertTrue(stats.totalAllocations > 0)
        assertTrue(stats.totalDeallocations > 0)
    }

    @Test
    fun `test different buffer sizes`() {
        val smallBuffer = memoryManager.acquireByteBuffer(512)    // 应该从小缓冲池获取1KB
        val mediumBuffer = memoryManager.acquireByteBuffer(32 * 1024) // 应该从中等缓冲池获取64KB
        val largeBuffer = memoryManager.acquireByteBuffer(512 * 1024) // 应该从大缓冲池获取1MB
        val extraLargeBuffer = memoryManager.acquireByteBuffer(2 * 1024 * 1024) // 直接分配
        
        assertNotNull(smallBuffer)
        assertNotNull(mediumBuffer)
        assertNotNull(largeBuffer)
        assertNotNull(extraLargeBuffer)
        
        assertEquals(2 * 1024 * 1024, extraLargeBuffer.capacity())
        
        memoryManager.releaseByteBuffer(smallBuffer)
        memoryManager.releaseByteBuffer(mediumBuffer)
        memoryManager.releaseByteBuffer(largeBuffer)
        memoryManager.releaseByteBuffer(extraLargeBuffer)
    }

    @Test
    fun `test buffer pool reuse`() {
        // 获取并释放一个缓冲区
        val buffer1 = memoryManager.acquireByteBuffer(1024)
        memoryManager.releaseByteBuffer(buffer1)
        
        // 再次获取相同大小的缓冲区，应该重用
        val buffer2 = memoryManager.acquireByteBuffer(1024)
        
        // 注意：由于池的实现，不能保证是同一个对象，但应该来自池
        assertNotNull(buffer2)
        assertEquals(1024, buffer2.capacity())
        
        memoryManager.releaseByteBuffer(buffer2)
    }

    @Test
    fun `test string builder pool`() {
        val sb = memoryManager.acquireStringBuilder()
        
        assertNotNull(sb)
        assertEquals(0, sb.length) // 应该是清空的
        
        sb.append("test content")
        assertEquals("test content", sb.toString())
        
        memoryManager.releaseStringBuilder(sb)
        
        // 再次获取，应该是清空的
        val sb2 = memoryManager.acquireStringBuilder()
        assertEquals(0, sb2.length)
        
        memoryManager.releaseStringBuilder(sb2)
    }

    @Test
    fun `test byte array pool`() {
        val array = memoryManager.acquireByteArray()
        
        assertNotNull(array)
        assertEquals(4096, array.size) // 默认大小
        
        // 修改数组内容
        array[0] = 42
        assertEquals(42, array[0])
        
        memoryManager.releaseByteArray(array)
        
        // 获取自定义大小的数组
        val customArray = memoryManager.acquireByteArray(2048)
        assertEquals(2048, customArray.size)
        
        memoryManager.releaseByteArray(customArray)
    }

    @Test
    fun `test int array pool`() {
        val array = memoryManager.acquireIntArray()
        
        assertNotNull(array)
        assertEquals(1024, array.size) // 默认大小
        
        array[0] = 123
        assertEquals(123, array[0])
        
        memoryManager.releaseIntArray(array)
        
        // 获取自定义大小的数组
        val customArray = memoryManager.acquireIntArray(512)
        assertEquals(512, customArray.size)
        
        memoryManager.releaseIntArray(customArray)
    }

    @Test
    fun `test long array pool`() {
        val array = memoryManager.acquireLongArray()
        
        assertNotNull(array)
        assertEquals(512, array.size) // 默认大小
        
        array[0] = 123456789L
        assertEquals(123456789L, array[0])
        
        memoryManager.releaseLongArray(array)
        
        // 获取自定义大小的数组
        val customArray = memoryManager.acquireLongArray(256)
        assertEquals(256, customArray.size)
        
        memoryManager.releaseLongArray(customArray)
    }

    @Test
    fun `test memory statistics`() {
        // 执行一些内存操作
        val buffer = memoryManager.acquireByteBuffer(1024)
        val sb = memoryManager.acquireStringBuilder()
        val array = memoryManager.acquireByteArray()
        
        val stats = memoryManager.getMemoryStats()
        
        assertTrue(stats.totalMemory > 0)
        assertTrue(stats.usedMemory >= 0)
        assertTrue(stats.freeMemory >= 0)
        assertTrue(stats.maxMemory > 0)
        assertTrue(stats.totalAllocations >= 3) // 至少3次分配
        assertTrue(stats.poolHitRate >= 0.0 && stats.poolHitRate <= 1.0)
        
        memoryManager.releaseByteBuffer(buffer)
        memoryManager.releaseStringBuilder(sb)
        memoryManager.releaseByteArray(array)
        
        val statsAfter = memoryManager.getMemoryStats()
        assertTrue(statsAfter.totalDeallocations >= 3) // 至少3次释放
    }

    @Test
    fun `test pool hit rate calculation`() {
        // 第一次获取，应该是池未命中
        val buffer1 = memoryManager.acquireByteBuffer(1024)
        memoryManager.releaseByteBuffer(buffer1)
        
        // 第二次获取，应该是池命中
        val buffer2 = memoryManager.acquireByteBuffer(1024)
        memoryManager.releaseByteBuffer(buffer2)
        
        val stats = memoryManager.getMemoryStats()
        // 命中率应该大于0（至少有一次命中）
        assertTrue(stats.poolHitRate > 0.0)
    }

    @Test
    fun `test concurrent access`() = runBlocking {
        val jobs = (1..10).map { threadId ->
            launch {
                repeat(100) {
                    val buffer = memoryManager.acquireByteBuffer(1024)
                    val sb = memoryManager.acquireStringBuilder()
                    val array = memoryManager.acquireByteArray()
                    
                    // 模拟一些工作
                    buffer.putInt(threadId)
                    sb.append("thread-$threadId")
                    array[0] = threadId.toByte()
                    
                    memoryManager.releaseByteBuffer(buffer)
                    memoryManager.releaseStringBuilder(sb)
                    memoryManager.releaseByteArray(array)
                }
            }
        }
        
        jobs.forEach { it.join() }

        val stats = memoryManager.getMemoryStats()
        // 由于并发性质，我们只验证基本的统计一致性
        assertTrue(stats.totalAllocations >= 3000) // 至少3000次分配
        assertTrue(stats.totalDeallocations >= 3000) // 至少3000次释放
        assertTrue(stats.totalAllocations >= stats.totalDeallocations) // 分配应该>=释放
    }

    @Test
    fun `test use byte buffer convenience function`() {
        val result = memoryManager.useByteBuffer(1024) { buffer ->
            buffer.putInt(42)
            buffer.flip()
            buffer.getInt()
        }
        
        assertEquals(42, result)
        
        // 验证缓冲区被自动释放
        val stats = memoryManager.getMemoryStats()
        assertEquals(stats.totalAllocations, stats.totalDeallocations)
    }

    @Test
    fun `test use string builder convenience function`() {
        val result = memoryManager.useStringBuilder { sb ->
            sb.append("Hello")
            sb.append(" ")
            sb.append("World")
            sb.toString()
        }
        
        assertEquals("Hello World", result)
        
        // 验证StringBuilder被自动释放
        val stats = memoryManager.getMemoryStats()
        assertEquals(stats.totalAllocations, stats.totalDeallocations)
    }

    @Test
    fun `test convenience functions handle exceptions`() {
        assertThrows(RuntimeException::class.java) {
            memoryManager.useByteBuffer(1024) { buffer ->
                buffer.putInt(42)
                throw RuntimeException("Test exception")
            }
        }
        
        // 即使发生异常，资源也应该被释放
        val stats = memoryManager.getMemoryStats()
        assertEquals(stats.totalAllocations, stats.totalDeallocations)
    }

    @Test
    fun `test force garbage collection`() {
        // 这个测试主要验证方法不会抛出异常
        assertDoesNotThrow {
            memoryManager.forceGC()
        }
    }

    @Test
    fun `test cleanup`() {
        // 分配一些资源
        val buffer = memoryManager.acquireByteBuffer(1024)
        val sb = memoryManager.acquireStringBuilder()
        
        memoryManager.releaseByteBuffer(buffer)
        memoryManager.releaseStringBuilder(sb)
        
        // 清理
        assertDoesNotThrow {
            memoryManager.cleanup()
        }
        
        // 清理后应该仍然可以正常使用
        val newBuffer = memoryManager.acquireByteBuffer(1024)
        assertNotNull(newBuffer)
        memoryManager.releaseByteBuffer(newBuffer)
    }

    @Test
    fun `test memory manager with heap memory`() {
        val heapMemoryManager = EnhancedMemoryManager(enableDirectMemory = false)
        
        val buffer = heapMemoryManager.acquireByteBuffer(1024)
        assertNotNull(buffer)
        assertFalse(buffer.isDirect) // 应该使用堆内存
        
        heapMemoryManager.releaseByteBuffer(buffer)
        heapMemoryManager.cleanup()
    }

    @Test
    fun `test pool size limits`() {
        // 分配大量相同大小的缓冲区
        val buffers = mutableListOf<ByteBuffer>()
        
        repeat(300) { // 超过池的最大大小
            buffers.add(memoryManager.acquireByteBuffer(1024))
        }
        
        // 释放所有缓冲区
        buffers.forEach { memoryManager.releaseByteBuffer(it) }
        
        val stats = memoryManager.getMemoryStats()
        // 小缓冲池的大小应该被限制
        assertTrue(stats.smallBufferPoolSize <= 200) // 池的最大大小
    }

    @Test
    fun `test memory cleanup task`() = runBlocking {
        // 创建一个短清理间隔的内存管理器
        val shortCleanupManager = EnhancedMemoryManager(cleanupInterval = kotlin.time.Duration.parse("100ms"))
        
        // 分配一些资源
        repeat(50) {
            val buffer = shortCleanupManager.acquireByteBuffer(1024)
            shortCleanupManager.releaseByteBuffer(buffer)
        }
        
        // 等待清理任务运行
        delay(200)
        
        // 清理任务应该正常运行（不会抛出异常）
        assertDoesNotThrow {
            shortCleanupManager.cleanup()
        }
    }
}
