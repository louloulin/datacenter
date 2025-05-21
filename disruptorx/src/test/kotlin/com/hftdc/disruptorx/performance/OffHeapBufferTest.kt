package com.hftdc.disruptorx.performance

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.random.Random

class OffHeapBufferTest {
    
    private lateinit var buffer: OffHeapBuffer
    private val testSize = 1024 * 1024 // 1MB
    
    @BeforeEach
    fun setUp() {
        buffer = OffHeapBuffer.allocate(testSize)
    }
    
    @AfterEach
    fun tearDown() {
        buffer.close()
    }
    
    @Test
    fun `verify buffer initialization`() {
        assertEquals(testSize, buffer.size)
        assertEquals(0, buffer.getPosition())
    }
    
    @Test
    fun `write and read byte array`() {
        val testData = ByteArray(1000) { it.toByte() }
        
        // 写入数据
        val position = buffer.write(testData)
        assertEquals(0, position) // 第一次写入应该从位置0开始
        assertEquals(testData.size.toLong(), buffer.getPosition())
        
        // 读取数据
        val readData = buffer.read(position, testData.size)
        assertArrayEquals(testData, readData)
    }
    
    @Test
    fun `write and read primitive values`() {
        // 写入Long和Int
        buffer.writeLong(0, 12345678901234L)
        buffer.writeInt(8, 987654321)
        
        // 读取并验证
        assertEquals(12345678901234L, buffer.readLong(0))
        assertEquals(987654321, buffer.readInt(8))
    }
    
    @Test
    fun `write multiple values and reset`() {
        val data1 = "Hello, world!".toByteArray()
        val data2 = "Testing off-heap buffer".toByteArray()
        
        buffer.write(data1)
        buffer.write(data2)
        
        assertEquals((data1.size + data2.size).toLong(), buffer.getPosition())
        
        // 重置缓冲区
        buffer.reset()
        assertEquals(0, buffer.getPosition())
        
        // 再次写入，确保可以正常工作
        val pos = buffer.write(data1)
        assertEquals(0, pos)
    }
    
    @Test
    fun `concurrent access to buffer`() {
        val numThreads = 10
        val itemsPerThread = 100
        val itemSize = 1000
        val executorService = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        
        // 记录每个线程写入的位置
        val positions = Array(numThreads) { LongArray(itemsPerThread) }
        val random = Random(42) // 固定种子以便复现
        
        // 多线程并发写入
        for (threadId in 0 until numThreads) {
            executorService.submit {
                try {
                    for (i in 0 until itemsPerThread) {
                        // 创建随机数据
                        val data = ByteArray(itemSize) { random.nextBytes(1)[0] }
                        // 写入并记录位置
                        positions[threadId][i] = buffer.write(data)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        
        // 等待所有线程完成
        latch.await()
        executorService.shutdown()
        
        // 验证总写入量
        assertEquals((numThreads * itemsPerThread * itemSize).toLong(), buffer.getPosition())
    }
    
    @Test
    fun `buffer tracking and statistics`() {
        // 初始计数
        val initialCount = OffHeapBuffer.getActiveBufferCount()
        val initialSize = OffHeapBuffer.getTotalAllocated()
        
        // 分配新缓冲区
        val newBuffer = OffHeapBuffer.allocate(testSize * 2)
        
        // 验证计数增加
        assertEquals(initialCount + 1, OffHeapBuffer.getActiveBufferCount())
        assertTrue(OffHeapBuffer.getTotalAllocated() >= initialSize + testSize * 2)
        
        // 关闭并验证计数减少
        newBuffer.close()
        assertEquals(initialCount, OffHeapBuffer.getActiveBufferCount())
    }
    
    @Test
    fun `out of bounds checks`() {
        // 写入一些数据
        val testData = ByteArray(100) { it.toByte() }
        buffer.write(testData)
        
        // 验证读取边界检查
        assertThrows(IndexOutOfBoundsException::class.java) {
            buffer.read(-1, 10)
        }
        
        assertThrows(IndexOutOfBoundsException::class.java) {
            buffer.read(testSize.toLong(), 10)
        }
        
        assertThrows(IndexOutOfBoundsException::class.java) {
            buffer.read(testSize - 5L, 10)
        }
    }
    
    @Test
    fun `write until buffer is full`() {
        val chunkSize = 1024
        var position: Long = 0
        
        // 不断写入，直到填满缓冲区
        var i = 0
        while (position + chunkSize <= testSize) {
            val data = ByteArray(chunkSize) { (i * chunkSize + it).toByte() }
            position = buffer.write(data)
            i++
        }
        
        // 验证写入了大约预期数量的块
        val expectedChunks = testSize / chunkSize
        assertEquals(expectedChunks, i)
        
        // 尝试写入超过缓冲区大小的数据，应该抛出异常
        val overflowData = ByteArray(chunkSize + 1) { it.toByte() }
        assertThrows(IllegalStateException::class.java) {
            buffer.write(overflowData)
        }
    }
} 