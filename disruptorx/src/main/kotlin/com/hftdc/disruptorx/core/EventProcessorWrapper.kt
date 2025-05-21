package com.hftdc.disruptorx.core

import com.hftdc.disruptorx.api.EventHandler
import com.lmax.disruptor.BatchEventProcessor
import com.lmax.disruptor.DataProvider
import com.lmax.disruptor.EventProcessor
import com.lmax.disruptor.ExceptionHandler
import com.lmax.disruptor.LifecycleAware
import com.lmax.disruptor.Sequence
import com.lmax.disruptor.SequenceBarrier
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BatchEventProcessor包装类，提供增强的批处理事件处理器功能
 *
 * @param T 事件类型
 * @property underlying 底层BatchEventProcessor实例
 * @property executor 执行器，用于启动处理器线程
 */
class EventProcessorWrapper<T>(
    private val underlying: EventProcessor,
    private val executor: Executor
) {
    private val running = AtomicBoolean(false)
    
    /**
     * 启动事件处理器
     */
    fun start() {
        if (running.compareAndSet(false, true)) {
            executor.execute(underlying)
        }
    }
    
    /**
     * 停止事件处理器
     */
    fun stop() {
        if (running.compareAndSet(true, false)) {
            underlying.halt()
        }
    }
    
    /**
     * 获取处理器序列
     * @return 序列对象
     */
    fun getSequence(): Sequence {
        return underlying.sequence
    }
    
    /**
     * 获取底层EventProcessor实例
     * @return 底层实例
     */
    fun unwrap(): EventProcessor {
        return underlying
    }
    
    /**
     * 是否正在运行
     * @return 运行状态
     */
    fun isRunning(): Boolean {
        return running.get()
    }
    
    companion object {
        /**
         * 创建批处理事件处理器包装实例
         *
         * @param dataProvider 数据提供者
         * @param sequenceBarrier 序列屏障
         * @param eventHandler 事件处理器
         * @param exceptionHandler 异常处理器
         * @param lifecycleAware 生命周期监听器
         * @param executor 执行器
         * @return 新的事件处理器包装实例
         */
        fun <T> createBatchProcessor(
            dataProvider: DataProvider<T>,
            sequenceBarrier: SequenceBarrier,
            eventHandler: EventHandler<T>,
            exceptionHandler: ExceptionHandler<T>? = null,
            lifecycleAware: LifecycleAware? = null,
            executor: Executor
        ): EventProcessorWrapper<T> {
            // 创建Disruptor原生事件处理器适配器
            val disruptorHandler = object : com.lmax.disruptor.EventHandler<T> {
                override fun onEvent(event: T, sequence: Long, endOfBatch: Boolean) {
                    eventHandler.onEvent(event, sequence, endOfBatch)
                }
            }
            
            // 创建BatchEventProcessor
            val processor = BatchEventProcessor<T>(dataProvider, sequenceBarrier, disruptorHandler)
            
            // 设置异常处理器
            if (exceptionHandler != null) {
                processor.setExceptionHandler(exceptionHandler)
            }
            
            return EventProcessorWrapper(processor, executor)
        }
    }
} 