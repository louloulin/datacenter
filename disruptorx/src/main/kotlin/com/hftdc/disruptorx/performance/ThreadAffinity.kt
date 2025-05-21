package com.hftdc.disruptorx.performance

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.lang.management.ManagementFactory

/**
 * 线程亲和性工具
 * 用于将线程绑定到特定CPU核心，提高缓存命中率和减少上下文切换
 */
object ThreadAffinity {
    // 是否支持线程亲和性设置
    private val isAffinitySupported = try {
        // 尝试获取CPU数量来判断是否支持亲和性设置
        Runtime.getRuntime().availableProcessors() > 0
    } catch (e: Exception) {
        false
    }
    
    // 跟踪已分配的核心
    private val allocatedCores = AtomicInteger(0)
    
    // 线程ID计数器
    private val nextThreadId = AtomicLong(0)
    
    /**
     * 获取系统CPU核心数
     * @return CPU核心数
     */
    fun getCpuCount(): Int {
        return Runtime.getRuntime().availableProcessors()
    }
    
    /**
     * 获取支持亲和性的线程工厂，优先使用指定的CPU核心组
     * @param name 线程名称前缀
     * @param cpuIds 优先使用的CPU ID列表，如果为空则自动选择
     * @return 线程工厂
     */
    fun newThreadFactory(name: String, cpuIds: List<Int> = emptyList()): ThreadFactory {
        return if (isAffinitySupported && cpuIds.isNotEmpty()) {
            AffinityThreadFactory(name, cpuIds)
        } else {
            DefaultThreadFactory(name)
        }
    }
    
    /**
     * 为当前线程分配CPU亲和性
     * @param cpuId 要绑定的CPU ID，-1表示自动选择
     * @return 亲和性锁对象，用于释放亲和性设置，如果不支持则返回null
     */
    fun bindCurrentThread(cpuId: Int = -1): AutoCloseable? {
        if (!isAffinitySupported) {
            return null
        }
        
        return try {
            // 由于不使用外部库，这里提供一个模拟实现
            // 实际生产环境中应使用JNA或其他方式实现真正的线程亲和性设置
            object : AutoCloseable {
                init {
                    // 模拟绑定线程到CPU
                    val actualCpuId = if (cpuId >= 0) cpuId else 
                        allocatedCores.getAndIncrement() % getCpuCount()
                    
                    // 在实际实现中，这里应该调用native方法设置线程亲和性
                    println("模拟: 绑定线程 ${Thread.currentThread().name} 到CPU核心 $actualCpuId")
                }
                
                override fun close() {
                    // 模拟释放线程亲和性
                    println("模拟: 释放线程 ${Thread.currentThread().name} 的CPU亲和性")
                }
            }
        } catch (e: Exception) {
            println("无法设置线程亲和性: ${e.message}")
            null
        }
    }
    
    /**
     * 创建一个带有线程亲和性的执行器服务
     * @param nThreads 线程数
     * @param namePrefix 线程名称前缀
     * @param cpuIds 优先使用的CPU ID列表，如果为空则自动选择
     * @return 执行器服务
     */
    fun newFixedThreadPool(nThreads: Int, namePrefix: String, cpuIds: List<Int> = emptyList()): ExecutorService {
        val threadFactory = newThreadFactory(namePrefix, cpuIds)
        return Executors.newFixedThreadPool(nThreads, threadFactory)
    }
    
    /**
     * 为高优先级处理分配CPU核心
     * @param count 需要分配的核心数量
     * @return 分配的CPU核心ID列表，如果不支持亲和性则返回空列表
     */
    fun allocateCoresForCriticalProcessing(count: Int): List<Int> {
        if (!isAffinitySupported || count <= 0) {
            return emptyList()
        }
        
        val cpuCount = getCpuCount()
        val result = mutableListOf<Int>()
        
        // 简单分配策略：顺序分配核心
        synchronized(ThreadAffinity::class.java) {
            val startCore = allocatedCores.get()
            for (i in 0 until count) {
                val coreId = (startCore + i) % cpuCount
                result.add(coreId)
            }
            allocatedCores.set((startCore + count) % cpuCount)
        }
        
        return result
    }
    
    /**
     * 检查当前系统是否是NUMA架构
     * @return 是否为NUMA架构
     */
    fun isNumaArchitecture(): Boolean {
        // 简单启发式检测：如果处理器数量大于物理处理器数量的2倍，可能是NUMA架构
        // 实际生产环境中应使用JNA或其他方式进行真正的NUMA检测
        val processors = ManagementFactory.getOperatingSystemMXBean()
            .availableProcessors
        
        // 假设超过8个核心的系统可能具有NUMA架构
        return processors >= 8
    }
    
    /**
     * 获取当前NUMA节点数
     * @return NUMA节点数，非NUMA架构返回1
     */
    fun getNumaNodeCount(): Int {
        // 简单启发式检测，实际应基于硬件信息
        return if (isNumaArchitecture()) {
            // 简单假设：每4个处理器核心属于一个NUMA节点
            val processors = Runtime.getRuntime().availableProcessors()
            Math.max(1, processors / 4)
        } else {
            1
        }
    }
    
    /**
     * 带CPU亲和性的线程工厂实现
     */
    private class AffinityThreadFactory(
        private val namePrefix: String,
        private val cpuIds: List<Int>
    ) : ThreadFactory {
        private val threadNumber = AtomicInteger(1)
        
        override fun newThread(r: Runnable): Thread {
            val thread = Thread(r, "$namePrefix-thread-${threadNumber.getAndIncrement()}")
            if (thread.isDaemon) {
                thread.isDaemon = false
            }
            if (thread.priority != Thread.NORM_PRIORITY) {
                thread.priority = Thread.NORM_PRIORITY
            }
            
            // 包装Runnable以设置线程亲和性
            return Thread {
                // 计算此线程应绑定的CPU核心
                val threadIndex = threadNumber.get() - 1
                val cpuId = cpuIds[threadIndex % cpuIds.size]
                
                // 绑定线程到指定CPU核心
                bindCurrentThread(cpuId)?.use {
                    // 执行原始任务
                    r.run()
                } ?: r.run() // 如果绑定失败，仍然执行任务
            }.apply {
                name = thread.name
                isDaemon = thread.isDaemon
                priority = thread.priority
            }
        }
    }
    
    /**
     * 默认线程工厂实现，当不支持亲和性设置时使用
     */
    private class DefaultThreadFactory(private val namePrefix: String) : ThreadFactory {
        private val threadNumber = AtomicInteger(1)
        
        override fun newThread(r: Runnable): Thread {
            val thread = Thread(r, "$namePrefix-thread-${threadNumber.getAndIncrement()}")
            if (thread.isDaemon) {
                thread.isDaemon = false
            }
            if (thread.priority != Thread.NORM_PRIORITY) {
                thread.priority = Thread.NORM_PRIORITY
            }
            return thread
        }
    }
} 