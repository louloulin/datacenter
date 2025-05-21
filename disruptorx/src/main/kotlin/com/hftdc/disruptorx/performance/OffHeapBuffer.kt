package com.hftdc.disruptorx.performance

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 堆外内存缓冲区
 * 用于管理堆外内存，减少GC压力并提供更高性能的内存访问
 */
class OffHeapBuffer private constructor(
    private val buffer: ByteBuffer,
    val size: Int
) : AutoCloseable {
    // 记录当前位置
    private val position = AtomicLong(0)
    
    // 读写锁保护并发访问
    private val lock = ReentrantReadWriteLock()
    
    /**
     * 将数据写入堆外内存
     * @param bytes 要写入的字节数组
     * @return 写入数据的起始位置
     */
    fun write(bytes: ByteArray): Long {
        return lock.write {
            val pos = position.get()
            if (pos + bytes.size > size) {
                throw IllegalStateException("缓冲区剩余空间不足: 需要 ${bytes.size} 字节，但只有 ${size - pos} 字节可用")
            }
            
            buffer.position(pos.toInt())
            buffer.put(bytes)
            
            val newPos = pos + bytes.size
            position.set(newPos)
            pos
        }
    }
    
    /**
     * 从指定位置读取指定长度的数据
     * @param position 起始位置
     * @param length 读取长度
     * @return 读取的字节数组
     */
    fun read(position: Long, length: Int): ByteArray {
        return lock.read {
            if (position < 0 || position >= size) {
                throw IndexOutOfBoundsException("位置超出范围: $position")
            }
            if (position + length > size) {
                throw IndexOutOfBoundsException("读取长度超出范围: 位置 $position, 长度 $length, 缓冲区大小 $size")
            }
            
            val result = ByteArray(length)
            buffer.position(position.toInt())
            buffer.get(result)
            result
        }
    }
    
    /**
     * 在指定位置写入一个long值
     * @param position 写入位置
     * @param value 要写入的long值
     */
    fun writeLong(position: Long, value: Long) {
        lock.write {
            buffer.putLong(position.toInt(), value)
        }
    }
    
    /**
     * 读取指定位置的long值
     * @param position 读取位置
     * @return 读取的long值
     */
    fun readLong(position: Long): Long {
        return lock.read {
            buffer.getLong(position.toInt())
        }
    }
    
    /**
     * 在指定位置写入一个int值
     * @param position 写入位置
     * @param value 要写入的int值
     */
    fun writeInt(position: Long, value: Int) {
        lock.write {
            buffer.putInt(position.toInt(), value)
        }
    }
    
    /**
     * 读取指定位置的int值
     * @param position 读取位置
     * @return 读取的int值
     */
    fun readInt(position: Long): Int {
        return lock.read {
            buffer.getInt(position.toInt())
        }
    }
    
    /**
     * 重置缓冲区位置到起始位置
     */
    fun reset() {
        lock.write {
            position.set(0)
        }
    }
    
    /**
     * 获取当前位置
     * @return 当前位置
     */
    fun getPosition(): Long {
        return position.get()
    }
    
    /**
     * 释放堆外内存
     */
    override fun close() {
        if (buffer.isDirect) {
            CLEANER.freeBuffer(buffer)
            ACTIVE_BUFFERS.remove(this)
        }
    }
    
    companion object {
        // 跟踪所有活动的堆外缓冲区
        private val ACTIVE_BUFFERS = ConcurrentHashMap<OffHeapBuffer, Boolean>()
        
        // 堆外内存清理器
        private val CLEANER = BufferCleaner()
        
        // 总分配内存大小 (字节)
        private val totalAllocated = AtomicLong(0)
        
        /**
         * 分配一个新的堆外内存缓冲区
         * @param size 缓冲区大小（字节）
         * @param bigEndian 是否使用大端字节序
         * @return 新分配的堆外缓冲区
         */
        fun allocate(size: Int, bigEndian: Boolean = true): OffHeapBuffer {
            val byteOrder = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
            val buffer = ByteBuffer.allocateDirect(size).order(byteOrder)
            val result = OffHeapBuffer(buffer, size)
            
            ACTIVE_BUFFERS[result] = true
            totalAllocated.addAndGet(size.toLong())
            
            return result
        }
        
        /**
         * 获取当前总分配的堆外内存大小（字节）
         * @return 堆外内存大小
         */
        fun getTotalAllocated(): Long {
            return totalAllocated.get()
        }
        
        /**
         * 获取活动的堆外缓冲区数量
         * @return 活动缓冲区数量
         */
        fun getActiveBufferCount(): Int {
            return ACTIVE_BUFFERS.size
        }
        
        /**
         * 释放所有堆外缓冲区
         */
        fun releaseAll() {
            ACTIVE_BUFFERS.keys.forEach { it.close() }
            ACTIVE_BUFFERS.clear()
            totalAllocated.set(0)
        }
    }
}

/**
 * ByteBuffer清理器
 * 用于释放堆外内存
 */
private class BufferCleaner {
    /**
     * 释放直接缓冲区
     * @param buffer 要释放的缓冲区
     */
    fun freeBuffer(buffer: ByteBuffer) {
        try {
            // 使用sun.misc.Cleaner或java.nio.DirectByteBuffer.cleaner()方法清理堆外内存
            // 注意：由于Java 9+的模块系统限制，这里使用反射实现
            if (buffer.isDirect) {
                // 获取cleaner方法
                val cleanerMethod = buffer.javaClass.getDeclaredMethod("cleaner")
                cleanerMethod.isAccessible = true
                val cleaner = cleanerMethod.invoke(buffer)
                
                // 调用clean方法
                val cleanMethod = cleaner.javaClass.getDeclaredMethod("clean")
                cleanMethod.isAccessible = true
                cleanMethod.invoke(cleaner)
            }
        } catch (e: Exception) {
            // 记录错误但不抛出异常，因为这只是一个最佳努力的清理操作
            println("无法清理直接缓冲区: ${e.message}")
        }
    }
} 