package com.hftdc.disruptorx.benchmark

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.api.Workflow
import com.hftdc.disruptorx.dsl.workflow
import com.hftdc.disruptorx.performance.LatencyRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.HdrHistogram.Histogram
import java.io.File
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.system.measureNanoTime

/**
 * DisruptorX框架基准测试
 * 用于评估DisruptorX在不同负载和配置下的性能表现
 */
class DisruptorXBenchmark {

    // 测试配置
    data class BenchmarkConfig(
        val testDurationSeconds: Int = 30,
        val warmupTimeSeconds: Int = 10,
        val messageRate: Int = 10000, // 每秒消息数
        val messageSize: Int = 1024,  // 消息大小（字节）
        val nodeCount: Int = 1,       // 节点数量
        val producerThreads: Int = 4, // 生产者线程数
        val consumerThreads: Int = 4, // 消费者线程数
        val reportIntervalMs: Long = 1000, // 报告间隔（毫秒）
        val batchSize: Int = 100     // 批处理大小
    )
    
    // 测试结果
    data class BenchmarkResult(
        val totalMessages: Long,
        val duration: Long,           // 纳秒
        val throughput: Double,       // 每秒消息数
        val latencyHistogram: Histogram,
        val messageRateHistogram: Histogram,
        val errorCount: Int
    )
    
    // 测试消息
    data class BenchmarkMessage(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.nanoTime(),
        val payload: ByteArray = ByteArray(1024) { 0 },
        var receivedTimestamp: Long = 0
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as BenchmarkMessage
            return id == other.id
        }
        
        override fun hashCode(): Int = id.hashCode()
    }
    
    // 消息处理器
    class MessageHandler(private val latencyRecorder: LatencyRecorder) {
        fun handleMessage(message: BenchmarkMessage): BenchmarkMessage {
            message.receivedTimestamp = System.nanoTime()
            val latency = message.receivedTimestamp - message.timestamp
            latencyRecorder.recordLatency(latency)
            return message
        }
    }
    
    // 执行基准测试
    fun runBenchmark(config: BenchmarkConfig): BenchmarkResult = runBlocking {
        println("开始执行DisruptorX基准测试...")
        println("配置: $config")
        
        // 创建延迟记录器
        val latencyRecorder = LatencyRecorder()
        val messageRateRecorder = LatencyRecorder(
            lowestTrackableValueNanos = 1,
            highestTrackableValueNanos = TimeUnit.SECONDS.toNanos(10),
            numberOfSignificantValueDigits = 2
        )
        
        // 创建节点和工作流
        val nodes = createNodes(config.nodeCount)
        val workflows = createWorkflows(nodes, latencyRecorder)
        
        // 计数器
        val messageCounter = AtomicLong(0)
        val errorCounter = AtomicInteger(0)
        val activeCounter = AtomicInteger(0)
        val testTopic = "benchmark-test-topic"
        
        // 等待消费者就绪的闭锁
        val consumerReadyLatch = CountDownLatch(config.nodeCount * config.consumerThreads)
        
        // 创建测试执行器
        val producerExecutor = Executors.newFixedThreadPool(config.producerThreads)
        val consumerExecutor = Executors.newFixedThreadPool(config.consumerThreads)
        
        try {
            // 设置消费者
            nodes.forEach { node ->
                repeat(config.consumerThreads) {
                    consumerExecutor.submit {
                        try {
                            // 订阅消息
                            node.eventBus.subscribe(testTopic) { message ->
                                try {
                                    activeCounter.incrementAndGet()
                                    MessageHandler(latencyRecorder).handleMessage(message as BenchmarkMessage)
                                    messageCounter.incrementAndGet()
                                } catch (e: Exception) {
                                    errorCounter.incrementAndGet()
                                    println("消费者错误: ${e.message}")
                                } finally {
                                    activeCounter.decrementAndGet()
                                }
                            }
                            
                            // 通知消费者就绪
                            consumerReadyLatch.countDown()
                        } catch (e: Exception) {
                            errorCounter.incrementAndGet()
                            println("消费者初始化错误: ${e.message}")
                        }
                    }
                }
            }
            
            // 等待所有消费者就绪
            consumerReadyLatch.await(30, TimeUnit.SECONDS)
            println("所有消费者已就绪")
            
            // 准备发布参数
            val testStartTime = System.nanoTime()
            val testEndTime = testStartTime + TimeUnit.SECONDS.toNanos(config.testDurationSeconds.toLong())
            val warmupEndTime = testStartTime + TimeUnit.SECONDS.toNanos(config.warmupTimeSeconds.toLong())
            val messageInterval = 1_000_000_000L / config.messageRate // 纳秒
            
            // 设置监控
            val monitorExecutor = Executors.newSingleThreadScheduledExecutor()
            monitorExecutor.scheduleAtFixedRate({
                val elapsedNanos = System.nanoTime() - testStartTime
                val elapsedSeconds = elapsedNanos / 1_000_000_000.0
                val messagesProcessed = messageCounter.get()
                val currentThroughput = messagesProcessed / elapsedSeconds
                
                println(String.format(
                    "进度: %.1f%%, 已处理: %d, 吞吐量: %.2f msg/s, 活跃消息: %d, 错误: %d",
                    100.0 * elapsedNanos / (testEndTime - testStartTime),
                    messagesProcessed,
                    currentThroughput,
                    activeCounter.get(),
                    errorCounter.get()
                ))
            }, 0, config.reportIntervalMs, TimeUnit.MILLISECONDS)
            
            // 创建生产者任务
            coroutineScope {
                val tasks = (0 until config.producerThreads).map { producerId ->
                    async(Dispatchers.IO) {
                        val node = nodes[producerId % nodes.size]
                        var lastSendTime = System.nanoTime()
                        var messagesInBatch = 0
                        val messages = mutableListOf<BenchmarkMessage>()
                        
                        while (System.nanoTime() < testEndTime) {
                            val now = System.nanoTime()
                            
                            // 判断是否需要发送消息
                            if (now >= lastSendTime + messageInterval) {
                                val isWarmup = now < warmupEndTime
                                
                                // 创建消息
                                val message = BenchmarkMessage(
                                    payload = ByteArray(config.messageSize) { (it % 256).toByte() }
                                )
                                
                                messages.add(message)
                                messagesInBatch++
                                
                                // 批量发送
                                if (messagesInBatch >= config.batchSize) {
                                    try {
                                        val batchSendNanos = measureNanoTime {
                                            // 发布消息
                                            messages.forEach { msg ->
                                                node.eventBus.publish(msg, testTopic)
                                            }
                                        }
                                        
                                        if (!isWarmup) {
                                            messageRateRecorder.recordLatency(batchSendNanos / messagesInBatch)
                                        }
                                    } catch (e: Exception) {
                                        errorCounter.incrementAndGet()
                                        println("发布错误: ${e.message}")
                                    }
                                    
                                    messages.clear()
                                    messagesInBatch = 0
                                }
                                
                                lastSendTime = now
                            } else {
                                // 高效使用CPU
                                TimeUnit.MICROSECONDS.sleep(1)
                            }
                        }
                        
                        // 发送剩余的消息
                        if (messages.isNotEmpty()) {
                            try {
                                messages.forEach { msg ->
                                    node.eventBus.publish(msg, testTopic)
                                }
                            } catch (e: Exception) {
                                errorCounter.incrementAndGet()
                                println("发布剩余消息错误: ${e.message}")
                            }
                        }
                    }
                }
                
                // 等待所有生产者完成
                println("等待所有生产者完成...")
                tasks.awaitAll()
            }
            
            // 关闭监控
            monitorExecutor.shutdown()
            monitorExecutor.awaitTermination(5, TimeUnit.SECONDS)
            
            // 等待剩余的消息处理完成
            println("等待剩余消息处理完成...")
            val maxWaitTime = 10L // 最长等待10秒
            val waitStart = System.currentTimeMillis()
            
            while (activeCounter.get() > 0 && 
                  (System.currentTimeMillis() - waitStart) < maxWaitTime * 1000) {
                TimeUnit.MILLISECONDS.sleep(100)
            }
            
            // 生成测试结果
            val totalMessages = messageCounter.get()
            val duration = System.nanoTime() - testStartTime
            val throughput = totalMessages * 1_000_000_000.0 / duration
            
            BenchmarkResult(
                totalMessages = totalMessages,
                duration = duration,
                throughput = throughput,
                latencyHistogram = latencyRecorder.getHistogram(),
                messageRateHistogram = messageRateRecorder.getHistogram(),
                errorCount = errorCounter.get()
            )
        } finally {
            // 关闭资源
            producerExecutor.shutdown()
            consumerExecutor.shutdown()
            producerExecutor.awaitTermination(5, TimeUnit.SECONDS)
            consumerExecutor.awaitTermination(5, TimeUnit.SECONDS)
            
            // 关闭节点
            nodes.forEach { it.shutdown() }
        }
    }
    
    // 创建测试节点
    private fun createNodes(count: Int): List<com.hftdc.disruptorx.DisruptorXNode> {
        val nodes = mutableListOf<com.hftdc.disruptorx.DisruptorXNode>()
        val basePort = 20000
        
        for (i in 0 until count) {
            val nodeId = "benchmark-node-${i+1}"
            val port = basePort + i
            
            val config = DisruptorXConfig(
                nodeId = nodeId,
                host = "localhost",
                port = port,
                nodeRole = if (i == 0) NodeRole.COORDINATOR else NodeRole.WORKER,
                seedNodes = listOf("localhost:$basePort")
            )
            
            val node = DisruptorX.createNode(config)
            node.initialize()
            nodes.add(node)
            
            println("创建节点: $nodeId (localhost:$port)")
        }
        
        return nodes
    }
    
    // 创建测试工作流
    private fun createWorkflows(
        nodes: List<com.hftdc.disruptorx.DisruptorXNode>,
        latencyRecorder: LatencyRecorder
    ): List<Workflow> {
        val workflows = mutableListOf<Workflow>()
        val workflowId = "benchmark-workflow"
        
        nodes.forEach { node ->
            val workflow = workflow(workflowId, "Benchmark Workflow") {
                source {
                    fromTopic("benchmark-test-topic")
                    partitionBy { message -> (message as BenchmarkMessage).id.hashCode() }
                }
                
                stages {
                    stage("processing") {
                        parallelism = 4
                        
                        handler { event ->
                            val message = event as BenchmarkMessage
                            MessageHandler(latencyRecorder).handleMessage(message)
                        }
                    }
                }
                
                sink {
                    toTopic("benchmark-result-topic")
                }
            }
            
            node.workflowManager.register(workflow)
            node.workflowManager.start(workflowId)
            workflows.add(workflow)
        }
        
        return workflows
    }
    
    // 生成报告
    fun generateReport(result: BenchmarkResult, config: BenchmarkConfig) {
        val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss")
        val timestamp = dateFormat.format(Date())
        val reportDir = File("benchmark-results/$timestamp")
        reportDir.mkdirs()
        
        val reportFile = File(reportDir, "report.txt")
        PrintStream(reportFile).use { out ->
            out.println("=== DisruptorX 基准测试报告 ===")
            out.println("时间: ${Date()}")
            out.println("\n=== 测试配置 ===")
            out.println("测试持续时间: ${config.testDurationSeconds}秒")
            out.println("节点数量: ${config.nodeCount}")
            out.println("生产者线程: ${config.producerThreads}")
            out.println("消费者线程: ${config.consumerThreads}")
            out.println("目标消息速率: ${config.messageRate}/秒")
            out.println("消息大小: ${config.messageSize}字节")
            out.println("批处理大小: ${config.batchSize}")
            
            out.println("\n=== 测试结果 ===")
            out.println("总消息数: ${result.totalMessages}")
            out.println("测试时长: ${result.duration / 1_000_000_000.0}秒")
            out.println("实际吞吐量: ${String.format("%.2f", result.throughput)}消息/秒")
            out.println("错误数: ${result.errorCount}")
            
            out.println("\n=== 延迟统计 (微秒) ===")
            out.println("最小延迟: ${result.latencyHistogram.minValue / 1000}")
            out.println("最大延迟: ${result.latencyHistogram.maxValue / 1000}")
            out.println("平均延迟: ${String.format("%.2f", result.latencyHistogram.mean / 1000)}")
            out.println("中位数延迟: ${result.latencyHistogram.getValueAtPercentile(50.0) / 1000}")
            out.println("90%延迟: ${result.latencyHistogram.getValueAtPercentile(90.0) / 1000}")
            out.println("99%延迟: ${result.latencyHistogram.getValueAtPercentile(99.0) / 1000}")
            out.println("99.9%延迟: ${result.latencyHistogram.getValueAtPercentile(99.9) / 1000}")
            out.println("99.99%延迟: ${result.latencyHistogram.getValueAtPercentile(99.99) / 1000}")
            
            out.println("\n=== 发布速率统计 (微秒/消息) ===")
            out.println("最小时间: ${result.messageRateHistogram.minValue / 1000}")
            out.println("最大时间: ${result.messageRateHistogram.maxValue / 1000}")
            out.println("平均时间: ${String.format("%.2f", result.messageRateHistogram.mean / 1000)}")
            out.println("中位数时间: ${result.messageRateHistogram.getValueAtPercentile(50.0) / 1000}")
            out.println("90%时间: ${result.messageRateHistogram.getValueAtPercentile(90.0) / 1000}")
            out.println("99%时间: ${result.messageRateHistogram.getValueAtPercentile(99.0) / 1000}")
        }
        
        // 导出延迟直方图
        File(reportDir, "latency_histogram.hgrm").outputStream().use { out ->
            result.latencyHistogram.outputPercentileDistribution(PrintStream(out), 1000.0)
        }
        
        // 导出发布速率直方图
        File(reportDir, "publishing_rate_histogram.hgrm").outputStream().use { out ->
            result.messageRateHistogram.outputPercentileDistribution(PrintStream(out), 1000.0)
        }
        
        println("报告已生成: ${reportFile.absolutePath}")
    }
    
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val benchmark = DisruptorXBenchmark()
            
            // 根据命令行参数创建配置
            val config = parseArgs(args)
            
            // 运行基准测试
            val result = benchmark.runBenchmark(config)
            
            // 生成报告
            benchmark.generateReport(result, config)
        }
        
        private fun parseArgs(args: Array<String>): BenchmarkConfig {
            val defaultConfig = BenchmarkConfig()
            
            // 如果没有参数，使用默认配置
            if (args.isEmpty()) {
                return defaultConfig
            }
            
            // 解析参数
            var duration = defaultConfig.testDurationSeconds
            var warmup = defaultConfig.warmupTimeSeconds
            var rate = defaultConfig.messageRate
            var messageSize = defaultConfig.messageSize
            var nodeCount = defaultConfig.nodeCount
            var producerThreads = defaultConfig.producerThreads
            var consumerThreads = defaultConfig.consumerThreads
            var batchSize = defaultConfig.batchSize
            
            for (i in args.indices) {
                when (args[i]) {
                    "-d", "--duration" -> if (i + 1 < args.size) duration = args[i + 1].toInt()
                    "-w", "--warmup" -> if (i + 1 < args.size) warmup = args[i + 1].toInt()
                    "-r", "--rate" -> if (i + 1 < args.size) rate = args[i + 1].toInt()
                    "-s", "--size" -> if (i + 1 < args.size) messageSize = args[i + 1].toInt()
                    "-n", "--nodes" -> if (i + 1 < args.size) nodeCount = args[i + 1].toInt()
                    "-p", "--producers" -> if (i + 1 < args.size) producerThreads = args[i + 1].toInt()
                    "-c", "--consumers" -> if (i + 1 < args.size) consumerThreads = args[i + 1].toInt()
                    "-b", "--batch" -> if (i + 1 < args.size) batchSize = args[i + 1].toInt()
                    "-h", "--help" -> {
                        printUsage()
                        System.exit(0)
                    }
                }
            }
            
            return BenchmarkConfig(
                testDurationSeconds = duration,
                warmupTimeSeconds = warmup,
                messageRate = rate,
                messageSize = messageSize,
                nodeCount = nodeCount,
                producerThreads = producerThreads,
                consumerThreads = consumerThreads,
                batchSize = batchSize
            )
        }
        
        private fun printUsage() {
            println("""
                DisruptorX基准测试工具
                
                用法: java -cp <classpath> com.hftdc.disruptorx.benchmark.DisruptorXBenchmark [选项]
                
                选项:
                  -d, --duration <秒>      测试持续时间 (默认: 30)
                  -w, --warmup <秒>        预热时间 (默认: 10)
                  -r, --rate <消息/秒>      目标消息速率 (默认: 10000)
                  -s, --size <字节>         消息大小 (默认: 1024)
                  -n, --nodes <数量>        节点数量 (默认: 1)
                  -p, --producers <数量>    生产者线程数 (默认: 4)
                  -c, --consumers <数量>    消费者线程数 (默认: 4)
                  -b, --batch <大小>        批处理大小 (默认: 100)
                  -h, --help              显示帮助信息
                
                示例:
                  java -cp <classpath> com.hftdc.disruptorx.benchmark.DisruptorXBenchmark -d 60 -r 50000 -n 3
            """.trimIndent())
        }
    }
} 