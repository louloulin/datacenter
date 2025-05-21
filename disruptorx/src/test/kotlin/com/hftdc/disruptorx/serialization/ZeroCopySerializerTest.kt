package com.hftdc.disruptorx.serialization

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.system.measureNanoTime

/**
 * ZeroCopySerializer测试类
 */
class ZeroCopySerializerTest {

    /**
     * 基础序列化测试数据类
     */
    data class TestData(
        var id: Long = 0L,
        var name: String = "",
        var value: Double = 0.0,
        var tags: List<String> = listOf(),
        var active: Boolean = false,
        var metadata: Map<String, String> = mapOf()
    )
    
    /**
     * 测试基础序列化/反序列化功能
     */
    @Test
    fun testBasicSerialization() {
        val serializer = ZeroCopySerializer()
        
        // 创建测试数据
        val testData = TestData(
            id = 1234L,
            name = "测试数据",
            value = 3.14159,
            tags = listOf("tag1", "tag2", "tag3"),
            active = true,
            metadata = mapOf("key1" to "value1", "key2" to "value2")
        )
        
        // 序列化
        val buffer = serializer.serialize(testData)
        
        // 反序列化
        val deserializedData = serializer.deserialize<TestData>(buffer)
        
        // 验证结果
        assertEquals(testData.id, deserializedData.id)
        assertEquals(testData.name, deserializedData.name)
        assertEquals(testData.value, deserializedData.value)
        assertEquals(testData.tags, deserializedData.tags)
        assertEquals(testData.active, deserializedData.active)
        assertEquals(testData.metadata, deserializedData.metadata)
        
        // 释放缓冲区
        serializer.release(buffer)
    }
    
    /**
     * 测试部分序列化/反序列化功能
     */
    @Test
    fun testDeltaSerialization() {
        val serializer = ZeroCopySerializer()
        
        // 创建基准数据
        val baselineData = TestData(
            id = 1234L,
            name = "基准数据",
            value = 2.71828,
            tags = listOf("base1", "base2"),
            active = false,
            metadata = mapOf("baseKey" to "baseValue")
        )
        
        // 创建更新数据 (只改变部分字段)
        val updatedData = baselineData.copy(
            name = "更新数据",
            active = true,
            metadata = mapOf("baseKey" to "baseValue", "newKey" to "newValue")
        )
        
        // 完整序列化
        val fullBuffer = serializer.serialize(updatedData)
        
        // 部分序列化
        val deltaBuffer = serializer.serializeDelta(updatedData, baselineData)
        
        // 验证增量序列化大小小于完整序列化
        assertTrue(deltaBuffer.remaining() < fullBuffer.remaining())
        println("完整序列化大小: ${fullBuffer.remaining()} 字节")
        println("增量序列化大小: ${deltaBuffer.remaining()} 字节")
        println("减少了: ${(1 - deltaBuffer.remaining().toDouble() / fullBuffer.remaining()) * 100}%")
        
        // 增量反序列化
        val deserializedData = serializer.deserializeDelta(deltaBuffer, baselineData.copy())
        
        // 验证结果
        assertEquals(updatedData.id, deserializedData.id)
        assertEquals(updatedData.name, deserializedData.name)
        assertEquals(updatedData.value, deserializedData.value)
        assertEquals(updatedData.tags, deserializedData.tags)
        assertEquals(updatedData.active, deserializedData.active)
        assertEquals(updatedData.metadata, deserializedData.metadata)
        
        // 释放缓冲区
        serializer.release(fullBuffer)
        serializer.release(deltaBuffer)
    }
    
    /**
     * 测试字符串引用复用
     */
    @Test
    fun testStringReferences() {
        val serializer = ZeroCopySerializer()
        
        // 创建包含重复字符串的测试数据
        val repeatedString = "这是一个会被重复使用的很长的字符串，应该会被引用复用"
        val testData = TestData(
            id = 1234L,
            name = repeatedString,
            tags = listOf(repeatedString, repeatedString, repeatedString),
            metadata = mapOf(
                repeatedString to "value1",
                "key2" to repeatedString
            )
        )
        
        // 序列化
        val buffer = serializer.serialize(testData)
        
        // 反序列化
        val deserializedData = serializer.deserialize<TestData>(buffer)
        
        // 验证结果
        assertEquals(testData.name, deserializedData.name)
        assertEquals(testData.tags, deserializedData.tags)
        assertEquals(testData.metadata, deserializedData.metadata)
        
        // 验证引用是相同的对象
        val name = deserializedData.name
        val tag1 = deserializedData.tags[0]
        assertSame(name, tag1) // 应该是相同对象引用
        
        // 释放缓冲区
        serializer.release(buffer)
    }
    
    /**
     * 性能测试 - 与标准Java序列化对比
     */
    @Test
    fun testSerializationPerformance() {
        val zeroCopySerializer = ZeroCopySerializer()
        val iterations = 100_000
        
        // 创建测试数据
        val testData = TestData(
            id = 9999L,
            name = "性能测试数据",
            value = 123.456,
            tags = listOf("性能", "测试", "标签"),
            active = true,
            metadata = mapOf("测试键1" to "测试值1", "测试键2" to "测试值2")
        )
        
        // 预热
        println("预热中...")
        for (i in 0 until 1000) {
            val buffer = zeroCopySerializer.serialize(testData)
            zeroCopySerializer.deserialize<TestData>(buffer)
            zeroCopySerializer.release(buffer)
        }
        
        // 测试零拷贝序列化性能
        println("测试零拷贝序列化性能 ($iterations 次)...")
        var totalSerializeTime = 0L
        var totalDeserializeTime = 0L
        var totalSize = 0
        
        for (i in 0 until iterations) {
            val serializeTime = measureNanoTime {
                zeroCopySerializer.serialize(testData).also {
                    if (i == 0) totalSize = it.remaining()
                }
            }
            
            val buffer = zeroCopySerializer.serialize(testData)
            val deserializeTime = measureNanoTime {
                zeroCopySerializer.deserialize<TestData>(buffer)
            }
            
            zeroCopySerializer.release(buffer)
            
            totalSerializeTime += serializeTime
            totalDeserializeTime += deserializeTime
        }
        
        val avgSerializeTimeNanos = totalSerializeTime / iterations
        val avgDeserializeTimeNanos = totalDeserializeTime / iterations
        
        println("零拷贝序列化结果:")
        println("  平均序列化时间: ${avgSerializeTimeNanos} 纳秒 (${avgSerializeTimeNanos / 1000.0} 微秒)")
        println("  平均反序列化时间: ${avgDeserializeTimeNanos} 纳秒 (${avgDeserializeTimeNanos / 1000.0} 微秒)")
        println("  序列化大小: $totalSize 字节")
        println("  每秒吞吐量: ${1_000_000_000 / (avgSerializeTimeNanos + avgDeserializeTimeNanos)} 对象/秒")
        
        // 使用标准Java序列化作为对比
        println("\n测试标准Java序列化性能 ($iterations 次)...")
        totalSerializeTime = 0L
        totalDeserializeTime = 0L
        totalSize = 0
        
        for (i in 0 until iterations) {
            val serializeTime = measureNanoTime {
                val baos = java.io.ByteArrayOutputStream()
                val oos = java.io.ObjectOutputStream(baos)
                oos.writeObject(testData)
                oos.flush()
                val bytes = baos.toByteArray()
                if (i == 0) totalSize = bytes.size
                oos.close()
                baos.close()
            }
            
            val baos = java.io.ByteArrayOutputStream()
            val oos = java.io.ObjectOutputStream(baos)
            oos.writeObject(testData)
            oos.flush()
            val bytes = baos.toByteArray()
            oos.close()
            baos.close()
            
            val deserializeTime = measureNanoTime {
                val bais = java.io.ByteArrayInputStream(bytes)
                val ois = java.io.ObjectInputStream(bais)
                ois.readObject()
                ois.close()
                bais.close()
            }
            
            totalSerializeTime += serializeTime
            totalDeserializeTime += deserializeTime
        }
        
        val avgJavaSerializeTimeNanos = totalSerializeTime / iterations
        val avgJavaDeserializeTimeNanos = totalDeserializeTime / iterations
        
        println("标准Java序列化结果:")
        println("  平均序列化时间: ${avgJavaSerializeTimeNanos} 纳秒 (${avgJavaSerializeTimeNanos / 1000.0} 微秒)")
        println("  平均反序列化时间: ${avgJavaDeserializeTimeNanos} 纳秒 (${avgJavaDeserializeTimeNanos / 1000.0} 微秒)")
        println("  序列化大小: $totalSize 字节")
        println("  每秒吞吐量: ${1_000_000_000 / (avgJavaSerializeTimeNanos + avgJavaDeserializeTimeNanos)} 对象/秒")
        
        // 性能对比
        val serializeSpeedup = avgJavaSerializeTimeNanos.toDouble() / avgSerializeTimeNanos
        val deserializeSpeedup = avgJavaDeserializeTimeNanos.toDouble() / avgDeserializeTimeNanos
        println("\n性能对比 (零拷贝 vs 标准Java):")
        println("  序列化速度提升: ${String.format("%.2f", serializeSpeedup)}x")
        println("  反序列化速度提升: ${String.format("%.2f", deserializeSpeedup)}x")
    }
    
    /**
     * 并发测试
     */
    @Test
    fun testConcurrentUsage() {
        val serializer = ZeroCopySerializer()
        val threadCount = 8
        val iterationsPerThread = 10_000
        
        // 创建测试数据
        val testData = TestData(
            id = 9999L,
            name = "并发测试数据",
            value = 123.456,
            tags = listOf("并发", "测试", "标签"),
            active = true,
            metadata = mapOf("测试键1" to "测试值1", "测试键2" to "测试值2")
        )
        
        // 创建线程池
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        
        // 提交任务
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val buffer = serializer.serialize(testData)
                        val deserialized = serializer.deserialize<TestData>(buffer)
                        assertEquals(testData.id, deserialized.id)
                        serializer.release(buffer)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        
        // 等待所有线程完成
        latch.await()
        executor.shutdown()
        
        println("完成 ${threadCount * iterationsPerThread} 次并发序列化/反序列化操作")
    }
} 