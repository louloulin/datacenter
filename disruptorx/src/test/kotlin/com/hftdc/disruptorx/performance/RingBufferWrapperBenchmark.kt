package com.hftdc.disruptorx.performance

import com.hftdc.disruptorx.core.RingBufferWrapper
import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.YieldingWaitStrategy
import com.lmax.disruptor.dsl.ProducerType
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import java.util.concurrent.TimeUnit

/**
 * RingBufferWrapper性能基准测试
 * 运行方法: ./gradlew jmh
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class RingBufferWrapperBenchmark {

    // 测试事件类
    data class BenchmarkEvent(var value: Long = 0)
    
    // 测试参数
    @Param("256", "1024", "4096", "16384")
    private var bufferSize: Int = 0
    
    // 每次批量发布的大小
    @Param("1", "10", "100")
    private var batchSize: Int = 0
    
    // 测试资源
    private lateinit var wrapper: RingBufferWrapper<BenchmarkEvent>
    private val eventFactory = EventFactory<BenchmarkEvent> { BenchmarkEvent() }
    
    @Setup
    fun setup() {
        wrapper = RingBufferWrapper.create(
            factory = eventFactory,
            bufferSize = bufferSize,
            producerType = ProducerType.SINGLE,
            waitStrategy = YieldingWaitStrategy()
        )
    }
    
    @Benchmark
    fun measureSingleEventPublish(blackhole: Blackhole) {
        wrapper.publish { _, event ->
            event.value = System.nanoTime()
        }
        blackhole.consume(wrapper.getCursor())
    }
    
    @Benchmark
    fun measureBatchEventPublish(blackhole: Blackhole) {
        if (batchSize == 1) {
            wrapper.publish { _, event ->
                event.value = System.nanoTime()
            }
        } else {
            wrapper.publishBatch(batchSize) { _, event ->
                event.value = System.nanoTime()
            }
        }
        blackhole.consume(wrapper.getCursor())
    }
    
    @Benchmark
    fun measureTryPublish(blackhole: Blackhole) {
        val result = wrapper.tryPublish { _, event ->
            event.value = System.nanoTime()
        }
        blackhole.consume(result)
        blackhole.consume(wrapper.getCursor())
    }
    
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val options = OptionsBuilder()
                .include(RingBufferWrapperBenchmark::class.java.simpleName)
                .build()
            Runner(options).run()
        }
    }
} 