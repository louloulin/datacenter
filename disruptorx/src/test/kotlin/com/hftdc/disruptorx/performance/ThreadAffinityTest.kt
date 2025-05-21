package com.hftdc.disruptorx.performance

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ThreadAffinityTest {
    
    @Test
    fun `get CPU count`() {
        val cpuCount = ThreadAffinity.getCpuCount()
        assertTrue(cpuCount > 0, "CPU count should be greater than 0")
        println("检测到的CPU核心数: $cpuCount")
    }
    
    @Test
    fun `create thread factory`() {
        val threadFactory = ThreadAffinity.newThreadFactory("test-thread")
        assertNotNull(threadFactory, "Thread factory should not be null")
        
        val thread = threadFactory.newThread { println("Thread created") }
        assertNotNull(thread, "Created thread should not be null")
        assertEquals("test-thread-thread-1", thread.name, "Thread name should match pattern")
    }
    
    @Test
    fun `thread factory with specific CPU IDs`() {
        val cpuIds = listOf(0, 1)
        val threadFactory = ThreadAffinity.newThreadFactory("test-affinity", cpuIds)
        assertNotNull(threadFactory, "Thread factory with CPU IDs should not be null")
        
        val thread = threadFactory.newThread { println("Affinity thread created") }
        assertNotNull(thread, "Created affinity thread should not be null")
    }
    
    @Test
    fun `fixed thread pool with thread affinity`() {
        val nThreads = 2
        val executor = ThreadAffinity.newFixedThreadPool(nThreads, "affinity-pool")
        assertNotNull(executor, "Executor service should not be null")
        
        val latch = CountDownLatch(nThreads)
        for (i in 0 until nThreads) {
            executor.submit {
                try {
                    println("Task $i running on thread: ${Thread.currentThread().name}")
                } finally {
                    latch.countDown()
                }
            }
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All tasks should complete")
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate")
    }
    
    @Test
    fun `allocate cores for critical processing`() {
        val coreCount = 2
        val allocatedCores = ThreadAffinity.allocateCoresForCriticalProcessing(coreCount)
        
        // 即使系统不支持亲和性设置，也应该返回一个合理的结果
        if (allocatedCores.isEmpty()) {
            println("系统不支持CPU亲和性设置，或者核心分配失败")
        } else {
            assertEquals(coreCount, allocatedCores.size, "Should allocate requested number of cores")
            println("分配的核心ID: $allocatedCores")
        }
    }
    
    @Test
    fun `bind current thread to CPU`() {
        val lock = ThreadAffinity.bindCurrentThread()
        try {
            if (lock == null) {
                println("系统不支持线程亲和性设置，或者绑定失败")
            } else {
                println("当前线程已绑定到CPU核心")
            }
            // 无法验证实际绑定效果，但至少确保调用不会抛出异常
            assertDoesNotThrow { Thread.sleep(100) }
        } finally {
            lock?.close()
        }
    }
    
    @Test
    fun `check NUMA architecture`() {
        val isNuma = ThreadAffinity.isNumaArchitecture()
        val nodeCount = ThreadAffinity.getNumaNodeCount()
        
        println("系统是否为NUMA架构: $isNuma")
        println("NUMA节点数: $nodeCount")
        
        // 验证节点数与NUMA状态一致
        if (isNuma) {
            assertTrue(nodeCount > 1, "NUMA system should have more than one node")
        } else {
            assertEquals(1, nodeCount, "Non-NUMA system should report 1 node")
        }
    }
    
    @Test
    fun `parallel task execution with thread affinity`() {
        // 只有在支持亲和性设置的系统上才测试具体行为
        val cpuCount = ThreadAffinity.getCpuCount()
        if (cpuCount < 2) {
            println("系统CPU核心数不足，跳过此测试")
            return
        }
        
        val taskCount = 4
        val latch = CountDownLatch(taskCount)
        val executor = ThreadAffinity.newFixedThreadPool(taskCount, "parallel-test")
        
        val threadToCpuMap = mutableMapOf<String, Int>()
        
        for (i in 0 until taskCount) {
            executor.submit {
                try {
                    // 尝试获取当前线程绑定的CPU信息（如果可能）
                    val threadName = Thread.currentThread().name
                    ThreadAffinity.bindCurrentThread(i % cpuCount)?.use {
                        // 执行CPU密集型计算
                        var sum = 0.0
                        for (j in 0 until 10_000_000) {
                            sum += Math.sqrt(j.toDouble())
                        }
                        
                        threadToCpuMap[threadName] = i % cpuCount
                        println("线程 $threadName 完成计算，结果: $sum")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        
        assertTrue(latch.await(30, TimeUnit.SECONDS), "所有任务应在30秒内完成")
        executor.shutdown()
        
        println("线程到CPU的映射: $threadToCpuMap")
    }
} 