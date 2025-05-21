package com.hftdc.disruptorx.core

import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.EventTranslator
import com.lmax.disruptor.EventTranslatorOneArg
import com.lmax.disruptor.RingBuffer
import com.lmax.disruptor.Sequence
import com.lmax.disruptor.SequenceBarrier
import com.lmax.disruptor.WaitStrategy
import com.lmax.disruptor.dsl.ProducerType
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * RingBufferWrapper类，封装LMAX Disruptor的RingBuffer，提供额外功能
 *
 * @param T 事件类型
 * @property underlying 底层RingBuffer实例
 */
class RingBufferWrapper<T>(private val underlying: RingBuffer<T>) {

    /**
     * 获取底层RingBuffer
     * @return 底层RingBuffer
     */
    fun unwrap(): RingBuffer<T> = underlying

    /**
     * 发布单个事件
     * @param translator 事件转换器函数
     */
    fun publish(translator: (Long, T) -> Unit) {
        val eventTranslator = EventTranslator<T> { event, sequence -> 
            translator(sequence, event)
        }
        underlying.publishEvent(eventTranslator)
    }

    /**
     * 发布单个事件（带数据）
     * @param translator 事件转换器函数
     * @param data 要传递给转换器的数据
     */
    fun <A> publish(translator: (Long, T, A) -> Unit, data: A) {
        val eventTranslator = EventTranslatorOneArg<T, A> { event, sequence, arg ->
            translator(sequence, event, arg)
        }
        underlying.publishEvent(eventTranslator, data)
    }

    /**
     * 尝试发布事件，如果RingBuffer满则返回false
     * @param translator 事件转换器函数
     * @return 是否发布成功
     */
    fun tryPublish(translator: (Long, T) -> Unit): Boolean {
        val sequence = underlying.tryNext()
        if (sequence < 0) {
            return false
        }
        
        try {
            val event = underlying.get(sequence)
            translator(sequence, event)
        } finally {
            underlying.publish(sequence)
        }
        
        return true
    }
    
    /**
     * 创建序列屏障
     * @param sequencesToTrack 要跟踪的序列
     * @return 序列屏障
     */
    fun newBarrier(vararg sequencesToTrack: Sequence): SequenceBarrier {
        return underlying.newBarrier(*sequencesToTrack)
    }
    
    /**
     * 获取当前RingBuffer容量
     * @return RingBuffer大小
     */
    fun getBufferSize(): Int {
        return underlying.bufferSize
    }
    
    /**
     * 获取当前可用空间
     * @return 可用空间大小
     */
    fun remainingCapacity(): Long {
        return underlying.remainingCapacity()
    }
    
    /**
     * 获取当前游标位置
     * @return 当前序列号
     */
    fun getCursor(): Long {
        return underlying.cursor
    }
    
    /**
     * 请求指定数量的空间并批量发布事件
     * @param batchSize 批次大小
     * @param translator 事件转换器函数
     */
    fun publishBatch(batchSize: Int, translator: (Long, T) -> Unit) {
        val seq = underlying.next(batchSize)
        for (i in 0 until batchSize) {
            val current = seq - (batchSize - 1) + i
            translator(current, underlying.get(current))
        }
        underlying.publish(seq - (batchSize - 1), seq)
    }
    
    companion object {
        /**
         * 创建RingBufferWrapper实例
         *
         * @param factory 事件工厂
         * @param bufferSize 缓冲区大小，必须是2的幂
         * @param producerType 生产者类型
         * @param waitStrategy 等待策略
         * @return 新的RingBufferWrapper实例
         */
        fun <T> create(
            factory: EventFactory<T>,
            bufferSize: Int,
            producerType: ProducerType = ProducerType.MULTI,
            waitStrategy: WaitStrategy
        ): RingBufferWrapper<T> {
            val ringBuffer = RingBuffer.create(producerType, factory, bufferSize, waitStrategy)
            return RingBufferWrapper(ringBuffer)
        }
    }
} 