package com.hftdc.disruptorx.performance

import com.lmax.disruptor.BlockingWaitStrategy
import com.lmax.disruptor.BusySpinWaitStrategy
import com.lmax.disruptor.SleepingWaitStrategy
import com.lmax.disruptor.WaitStrategy
import com.lmax.disruptor.YieldingWaitStrategy
import com.lmax.disruptor.dsl.ProducerType
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Disruptor自动调优组件
 * 根据运行时指标自动调整配置参数
 */
class AutoTuner(
    private val name: String,
    private val outputDirectory: String = "tuner-results"
) {
    // 当前配置
    private var currentConfig = DisruptorConfig()
    
    // 性能指标
    private val throughputHistory = mutableListOf<Int>()
    private val latencyP99History = mutableListOf<Double>()
    
    // 优化状态
    private val isActive = AtomicBoolean(false)
    private val iterationCount = AtomicInteger(0)
    private val lastAdjustmentTime = AtomicLong(0)
    
    // 实时指标
    private val currentThroughput = AtomicInteger(0)
    private val currentLatencyP99 = AtomicLong(0)
    
    // 配置调整记录
    private val adjustmentHistory = mutableListOf<ConfigAdjustment>()
    
    // 配置缓存 - 记录已尝试过的配置及其性能
    private val configCache = ConcurrentHashMap<DisruptorConfig, PerformanceMetrics>()
    
    /**
     * 开始调优
     */
    fun start() {
        if (isActive.compareAndSet(false, true)) {
            println("启动自动调优: $name")
            lastAdjustmentTime.set(System.currentTimeMillis())
            
            // 创建输出目录
            val dir = File(outputDirectory)
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }
    
    /**
     * 停止调优
     */
    fun stop(): TuningReport {
        isActive.set(false)
        
        // 生成调优报告
        val report = TuningReport(
            name = name,
            iterations = iterationCount.get(),
            bestConfig = findBestConfig(),
            initialConfig = adjustmentHistory.firstOrNull()?.oldConfig ?: currentConfig,
            adjustments = adjustmentHistory.toList()
        )
        
        // 保存报告
        saveReport(report)
        
        return report
    }
    
    /**
     * 记录性能指标
     */
    fun recordMetrics(throughput: Int, latencyP99Micros: Double) {
        if (!isActive.get()) return
        
        // 更新实时指标
        currentThroughput.set(throughput)
        currentLatencyP99.set(latencyP99Micros.toLong())
        
        // 添加到历史记录
        throughputHistory.add(throughput)
        latencyP99History.add(latencyP99Micros)
        
        // 记录当前配置的性能
        val metrics = PerformanceMetrics(throughput, latencyP99Micros)
        configCache[currentConfig] = metrics
        
        // 检查是否需要调整配置
        checkAndAdjustConfig()
    }
    
    /**
     * 检查并调整配置
     */
    private fun checkAndAdjustConfig() {
        // 至少有一次性能度量后才开始调整
        if (throughputHistory.size < 1) return
        
        // 控制调整频率 - 至少间隔10秒
        val now = System.currentTimeMillis()
        if (now - lastAdjustmentTime.get() < 10000) return
        
        // 开始新一轮调整
        iterationCount.incrementAndGet()
        lastAdjustmentTime.set(now)
        
        // 当前性能指标
        val currentThroughput = throughputHistory.last()
        val currentLatencyP99 = latencyP99History.last()
        
        // 创建多个候选配置
        val candidates = generateCandidates(currentConfig)
        
        // 从候选配置中找出最佳配置
        var bestConfig = currentConfig
        var bestScore = calculateScore(currentThroughput, currentLatencyP99)
        
        for (candidate in candidates) {
            // 如果已经尝试过这个配置，使用缓存的性能数据
            val cachedMetrics = configCache[candidate]
            if (cachedMetrics != null) {
                val candidateScore = calculateScore(cachedMetrics.throughput, cachedMetrics.latencyP99)
                if (candidateScore > bestScore) {
                    bestScore = candidateScore
                    bestConfig = candidate
                }
            }
        }
        
        // 如果找到更好的配置，记录调整
        if (bestConfig != currentConfig) {
            val adjustment = ConfigAdjustment(
                timestamp = LocalDateTime.now(),
                oldConfig = currentConfig,
                newConfig = bestConfig,
                throughputBefore = currentThroughput,
                latencyP99Before = currentLatencyP99,
                reason = determineAdjustmentReason(currentConfig, bestConfig)
            )
            
            adjustmentHistory.add(adjustment)
            currentConfig = bestConfig
            
            println("调整Disruptor配置:")
            println("  - RingBuffer大小: ${currentConfig.ringBufferSize} → ${bestConfig.ringBufferSize}")
            println("  - 等待策略: ${currentConfig.waitStrategy.javaClass.simpleName} → ${bestConfig.waitStrategy.javaClass.simpleName}")
            println("  - 批处理大小: ${currentConfig.batchSize} → ${bestConfig.batchSize}")
        }
    }
    
    /**
     * 生成候选配置
     */
    private fun generateCandidates(baseConfig: DisruptorConfig): List<DisruptorConfig> {
        val candidates = mutableListOf<DisruptorConfig>()
        
        // 变化RingBuffer大小
        candidates.add(baseConfig.copy(ringBufferSize = adjustRingBufferSize(baseConfig.ringBufferSize, true)))
        candidates.add(baseConfig.copy(ringBufferSize = adjustRingBufferSize(baseConfig.ringBufferSize, false)))
        
        // 尝试不同等待策略
        val waitStrategies = listOf(
            YieldingWaitStrategy(),
            BusySpinWaitStrategy(),
            SleepingWaitStrategy(),
            BlockingWaitStrategy()
        )
        
        for (strategy in waitStrategies) {
            if (strategy.javaClass != baseConfig.waitStrategy.javaClass) {
                candidates.add(baseConfig.copy(waitStrategy = strategy))
            }
        }
        
        // 变化批处理大小
        candidates.add(baseConfig.copy(batchSize = adjustBatchSize(baseConfig.batchSize, true)))
        candidates.add(baseConfig.copy(batchSize = adjustBatchSize(baseConfig.batchSize, false)))
        
        // 变化生产者类型
        val oppositeProducerType = if (baseConfig.producerType == ProducerType.SINGLE) {
            ProducerType.MULTI
        } else {
            ProducerType.SINGLE
        }
        candidates.add(baseConfig.copy(producerType = oppositeProducerType))
        
        return candidates
    }
    
    /**
     * 调整RingBuffer大小 (必须是2的幂)
     */
    private fun adjustRingBufferSize(current: Int, increase: Boolean): Int {
        return if (increase) {
            min(current * 2, 1024 * 1024 * 16) // 最大16M
        } else {
            max(current / 2, 16) // 最小16
        }
    }
    
    /**
     * 调整批处理大小
     */
    private fun adjustBatchSize(current: Int, increase: Boolean): Int {
        return if (increase) {
            min(current * 2, 1000) // 最大1000
        } else {
            max(current / 2, 1) // 最小1
        }
    }
    
    /**
     * 计算配置得分 (平衡吞吐量和延迟)
     */
    private fun calculateScore(throughput: Int, latencyP99: Double): Double {
        // 归一化吞吐量 (假设最大目标是1000万/秒)
        val normalizedThroughput = min(throughput / 10_000_000.0, 1.0)
        
        // 归一化延迟 (假设最大可接受延迟是1000微秒)
        val normalizedLatency = max(0.0, 1.0 - (latencyP99 / 1000.0))
        
        // 综合得分 (更偏重吞吐量)
        return normalizedThroughput * 0.7 + normalizedLatency * 0.3
    }
    
    /**
     * 确定调整原因
     */
    private fun determineAdjustmentReason(oldConfig: DisruptorConfig, newConfig: DisruptorConfig): String {
        val reasons = mutableListOf<String>()
        
        if (oldConfig.ringBufferSize != newConfig.ringBufferSize) {
            val direction = if (newConfig.ringBufferSize > oldConfig.ringBufferSize) "增加" else "减少"
            reasons.add("${direction}RingBuffer大小以${if (direction == "增加") "提高吞吐量" else "减少内存使用"}")
        }
        
        if (oldConfig.waitStrategy.javaClass != newConfig.waitStrategy.javaClass) {
            when (newConfig.waitStrategy) {
                is BusySpinWaitStrategy -> reasons.add("切换到自旋等待以获得更低延迟")
                is YieldingWaitStrategy -> reasons.add("切换到让步等待以平衡延迟和CPU使用")
                is SleepingWaitStrategy -> reasons.add("切换到睡眠等待以减少CPU使用")
                is BlockingWaitStrategy -> reasons.add("切换到阻塞等待以最小化CPU使用")
            }
        }
        
        if (oldConfig.batchSize != newConfig.batchSize) {
            val direction = if (newConfig.batchSize > oldConfig.batchSize) "增加" else "减少"
            reasons.add("${direction}批处理大小以${if (direction == "增加") "提高吞吐量" else "减少延迟"}")
        }
        
        if (oldConfig.producerType != newConfig.producerType) {
            reasons.add("切换到${if (newConfig.producerType == ProducerType.SINGLE) "单" else "多"}生产者模式")
        }
        
        return reasons.joinToString(", ")
    }
    
    /**
     * 找出最佳配置
     */
    private fun findBestConfig(): DisruptorConfig {
        if (configCache.isEmpty()) return currentConfig
        
        var bestConfig = currentConfig
        var bestScore = Double.MIN_VALUE
        
        for ((config, metrics) in configCache) {
            val score = calculateScore(metrics.throughput, metrics.latencyP99)
            if (score > bestScore) {
                bestScore = score
                bestConfig = config
            }
        }
        
        return bestConfig
    }
    
    /**
     * 保存调优报告
     */
    private fun saveReport(report: TuningReport) {
        try {
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            val reportFile = File(outputDirectory, "${report.name.replace(" ", "_")}_tuning_report_$timestamp.txt")
            
            reportFile.printWriter().use { writer ->
                writer.println("====== Disruptor自动调优报告: ${report.name} ======")
                writer.println("调优时间: ${LocalDateTime.now()}")
                writer.println("调优迭代次数: ${report.iterations}")
                writer.println()
                
                writer.println("初始配置:")
                writer.println("  RingBuffer大小: ${report.initialConfig.ringBufferSize}")
                writer.println("  等待策略: ${report.initialConfig.waitStrategy.javaClass.simpleName}")
                writer.println("  批处理大小: ${report.initialConfig.batchSize}")
                writer.println("  生产者类型: ${report.initialConfig.producerType}")
                writer.println()
                
                writer.println("最佳配置:")
                writer.println("  RingBuffer大小: ${report.bestConfig.ringBufferSize}")
                writer.println("  等待策略: ${report.bestConfig.waitStrategy.javaClass.simpleName}")
                writer.println("  批处理大小: ${report.bestConfig.batchSize}")
                writer.println("  生产者类型: ${report.bestConfig.producerType}")
                writer.println()
                
                writer.println("配置调整历史:")
                for ((index, adjustment) in report.adjustments.withIndex()) {
                    writer.println("  ${index + 1}. 调整时间: ${adjustment.timestamp}")
                    writer.println("     调整原因: ${adjustment.reason}")
                    writer.println("     性能变化: ${adjustment.throughputBefore} ops/sec, ${adjustment.latencyP99Before} μs")
                    writer.println("     -------------------------------------")
                }
                
                writer.println()
                writer.println("所有测试过的配置 (${configCache.size}):")
                configCache.entries.sortedByDescending { 
                    calculateScore(it.value.throughput, it.value.latencyP99) 
                }.take(10).forEachIndexed { index, entry ->
                    val config = entry.key
                    val metrics = entry.value
                    val score = calculateScore(metrics.throughput, metrics.latencyP99)
                    
                    writer.println("  ${index + 1}. 得分: ${String.format("%.2f", score)}")
                    writer.println("     RingBuffer: ${config.ringBufferSize}, 等待策略: ${config.waitStrategy.javaClass.simpleName}")
                    writer.println("     批处理大小: ${config.batchSize}, 生产者类型: ${config.producerType}")
                    writer.println("     性能: ${metrics.throughput} ops/sec, ${metrics.latencyP99} μs")
                    writer.println()
                }
            }
            
            println("调优报告已保存到: ${reportFile.absolutePath}")
            
        } catch (e: Exception) {
            println("保存调优报告失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 获取当前推荐配置
     */
    fun getCurrentRecommendedConfig(): DisruptorConfig {
        return currentConfig
    }
    
    /**
     * Disruptor配置数据类
     */
    data class DisruptorConfig(
        val ringBufferSize: Int = 1024 * 8,
        val waitStrategy: WaitStrategy = YieldingWaitStrategy(),
        val batchSize: Int = 10,
        val producerType: ProducerType = ProducerType.MULTI
    )
    
    /**
     * 性能指标数据类
     */
    data class PerformanceMetrics(
        val throughput: Int,
        val latencyP99: Double
    )
    
    /**
     * 配置调整数据类
     */
    data class ConfigAdjustment(
        val timestamp: LocalDateTime,
        val oldConfig: DisruptorConfig,
        val newConfig: DisruptorConfig,
        val throughputBefore: Int,
        val latencyP99Before: Double,
        val reason: String
    )
    
    /**
     * 调优报告数据类
     */
    data class TuningReport(
        val name: String,
        val iterations: Int,
        val bestConfig: DisruptorConfig,
        val initialConfig: DisruptorConfig,
        val adjustments: List<ConfigAdjustment>
    )
} 