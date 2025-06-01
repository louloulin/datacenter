package com.hftdc.disruptorx.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 简化的EventBus测试 - 专注于核心功能
 */
class EventBusSimpleTest {

    // 测试事件类
    data class TestEvent(val id: String, val value: Int)
    
    private lateinit var eventBus: EventBus<TestEvent>
    
    @BeforeEach
    fun setup() {
        eventBus = eventBus<TestEvent>()
        eventBus.start()
    }
    
    @AfterEach
    fun cleanup() {
        eventBus.close()
    }

    @Test
    fun `测试最简单的3行代码启动`() {
        // 这就是api.md中承诺的3行代码启动
        val bus = eventBus<TestEvent>()
        val receivedEvent = AtomicReference<TestEvent>()
        val latch = CountDownLatch(1)
        
        bus.on { event -> 
            receivedEvent.set(event)
            latch.countDown()
        }
        bus.start()
        bus.emit(TestEvent("test-1", 100))
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "事件应该在2秒内被处理")
        assertNotNull(receivedEvent.get(), "应该接收到事件")
        assertEquals("test-1", receivedEvent.get().id)
        assertEquals(100, receivedEvent.get().value)
        
        bus.close()
        println("✅ 3行代码启动测试通过")
    }

    @Test
    fun `测试单个事件发布和订阅`() {
        val receivedEvent = AtomicReference<TestEvent>()
        val latch = CountDownLatch(1)
        
        eventBus.on { event ->
            receivedEvent.set(event)
            latch.countDown()
        }
        
        eventBus.emit(TestEvent("single-event", 42))
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "事件应该在2秒内被处理")
        assertNotNull(receivedEvent.get(), "应该接收到事件")
        assertEquals("single-event", receivedEvent.get().id)
        assertEquals(42, receivedEvent.get().value)
        
        println("✅ 单个事件发布和订阅测试通过")
    }

    @Test
    fun `测试异步事件处理`() = runBlocking {
        val receivedEvent = AtomicReference<TestEvent>()
        val latch = CountDownLatch(1)
        
        eventBus.onAsync { event ->
            // 模拟异步处理
            kotlinx.coroutines.delay(10)
            receivedEvent.set(event)
            latch.countDown()
        }
        
        eventBus.emitAsync(TestEvent("async-event", 123))
        
        assertTrue(latch.await(3, TimeUnit.SECONDS), "异步事件应该在3秒内处理完成")
        assertNotNull(receivedEvent.get(), "应该接收到异步事件")
        assertEquals("async-event", receivedEvent.get().id)
        assertEquals(123, receivedEvent.get().value)
        
        println("✅ 异步事件处理测试通过")
    }

    @Test
    fun `测试订阅取消`() {
        val receivedEvents = mutableListOf<TestEvent>()
        
        val subscription = eventBus.on { event ->
            synchronized(receivedEvents) {
                receivedEvents.add(event)
            }
        }
        
        // 发布第一个事件
        eventBus.emit(TestEvent("before-cancel", 1))
        Thread.sleep(100) // 等待处理
        
        // 取消订阅
        subscription.cancel()
        assertTrue(subscription.isCancelled, "订阅应该被取消")
        
        // 发布第二个事件
        eventBus.emit(TestEvent("after-cancel", 2))
        Thread.sleep(100) // 等待处理
        
        assertEquals(1, receivedEvents.size, "取消订阅后不应该接收到新事件")
        assertEquals("before-cancel", receivedEvents[0].id)
        
        println("✅ 订阅取消测试通过")
    }

    @Test
    fun `测试DSL配置`() {
        val configuredBus = eventBus<TestEvent> {
            ringBufferSize = 2048
            waitStrategy = WaitStrategy.BUSY_SPIN
            producerType = ProducerType.SINGLE
            
            performance {
                batchSize = 200
                enableZeroCopy = true
            }
            
            monitoring {
                metrics = true
                logging = LogLevel.DEBUG
            }
        }
        
        assertNotNull(configuredBus, "配置的事件总线应该创建成功")
        
        val receivedEvent = AtomicReference<TestEvent>()
        val latch = CountDownLatch(1)
        
        configuredBus.on { event ->
            receivedEvent.set(event)
            latch.countDown()
        }
        
        configuredBus.start()
        configuredBus.emit(TestEvent("config-test", 999))
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "配置的事件总线应该正常工作")
        assertEquals("config-test", receivedEvent.get().id)
        
        configuredBus.close()
        println("✅ DSL配置测试通过")
    }

    @Test
    fun `测试性能指标`() {
        val latch = CountDownLatch(10)
        
        eventBus.on { event ->
            latch.countDown()
        }
        
        // 发布10个事件
        repeat(10) { i ->
            eventBus.emit(TestEvent("perf-$i", i))
        }
        
        assertTrue(latch.await(3, TimeUnit.SECONDS), "性能测试事件应该被处理")
        
        val metrics = eventBus.metrics
        assertTrue(metrics.throughput >= 0, "吞吐量应该大于等于0")
        assertTrue(metrics.errorRate >= 0, "错误率应该大于等于0")
        assertNotNull(metrics.latency, "延迟统计应该存在")
        
        val health = eventBus.health
        assertTrue(health.isHealthy, "事件总线应该是健康的")
        assertEquals("RUNNING", health.status, "状态应该是RUNNING")
        
        println("✅ 性能指标测试通过")
        println("   吞吐量: ${metrics.throughput}")
        println("   错误率: ${metrics.errorRate}")
        println("   健康状态: ${health.status}")
    }

    @Test
    fun `测试批量发布`() {
        val receivedEvents = mutableListOf<TestEvent>()
        val latch = CountDownLatch(3)
        
        eventBus.on { event ->
            synchronized(receivedEvents) {
                receivedEvents.add(event)
            }
            latch.countDown()
        }
        
        val events = listOf(
            TestEvent("batch-1", 1),
            TestEvent("batch-2", 2),
            TestEvent("batch-3", 3)
        )
        
        eventBus.emitAll(events)
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "批量事件应该被处理")
        assertEquals(3, receivedEvents.size, "应该接收到3个批量事件")
        
        println("✅ 批量发布测试通过")
    }

    @Test
    fun `测试事件过滤`() {
        val highValueEvents = mutableListOf<TestEvent>()
        val latch = CountDownLatch(2)
        
        // 只处理value > 100的事件
        eventBus.filter { it.value > 100 }.on { event ->
            synchronized(highValueEvents) {
                highValueEvents.add(event)
            }
            latch.countDown()
        }
        
        // 发布不同value的事件
        eventBus.emit(TestEvent("low-1", 50))    // 应该被过滤
        eventBus.emit(TestEvent("high-1", 150))  // 应该通过
        eventBus.emit(TestEvent("low-2", 75))    // 应该被过滤
        eventBus.emit(TestEvent("high-2", 200))  // 应该通过
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "高价值事件应该被处理")
        assertEquals(2, highValueEvents.size, "应该只接收到2个高价值事件")
        assertTrue(highValueEvents.all { it.value > 100 }, "所有事件的value都应该大于100")
        
        println("✅ 事件过滤测试通过")
    }

    @Test
    fun `测试生命周期管理`() {
        val testBus = eventBus<TestEvent>()
        
        // 初始状态
        assertFalse(testBus.health.isHealthy, "未启动的事件总线应该不健康")
        
        // 启动
        testBus.start()
        assertTrue(testBus.health.isHealthy, "启动后的事件总线应该健康")
        assertEquals("RUNNING", testBus.health.status)
        
        // 停止
        testBus.stop()
        assertFalse(testBus.health.isHealthy, "停止后的事件总线应该不健康")
        assertEquals("STOPPED", testBus.health.status)
        
        // 关闭
        testBus.close()
        
        println("✅ 生命周期管理测试通过")
    }
}
