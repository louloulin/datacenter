package com.hftdc.disruptorx.integration

import com.hftdc.disruptorx.api.NodeInfo
import com.hftdc.disruptorx.api.NodeRole
import com.hftdc.disruptorx.api.NodeStatus
import com.hftdc.disruptorx.consensus.RaftConsensus
import com.hftdc.disruptorx.distributed.DistributedLockService
import com.hftdc.disruptorx.monitoring.ConsoleMetricsExporter
import com.hftdc.disruptorx.monitoring.MetricsCollector
import com.hftdc.disruptorx.workflow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * 增强功能集成测试
 * 测试分布式一致性、监控和工作流管理功能
 */
class EnhancedFeaturesTest {
    
    private lateinit var metricsCollector: MetricsCollector
    private lateinit var raftConsensus: RaftConsensus
    private lateinit var lockService: DistributedLockService
    private lateinit var workflowManager: EnhancedWorkflowManager
    
    @BeforeEach
    fun setup() {
        // 初始化指标收集器
        metricsCollector = MetricsCollector()
        metricsCollector.addExporter(ConsoleMetricsExporter())
        
        // 初始化 Raft 共识（单节点集群用于测试）
        val clusterNodes = listOf(
            NodeInfo("node1", "localhost", 8001, true, NodeRole.MIXED, NodeStatus.ACTIVE)
        )
        raftConsensus = RaftConsensus("node1", clusterNodes)
        
        // 启动 Raft 共识并等待成为 Leader
        runBlocking {
            raftConsensus.start()
            // 强制设置为 Leader 状态用于测试
            raftConsensus.becomeLeader()
        }
        
        // 初始化分布式锁服务
        lockService = DistributedLockService(raftConsensus, "node1")
        
        // 注册状态机应用器
        raftConsensus.setStateMachineApplier { data ->
            lockService.applyLogEntry(data)
        }
        
        // 初始化工作流管理器
        workflowManager = EnhancedWorkflowManager(metricsCollector)
    }
    
    @AfterEach
    fun cleanup() {
        runBlocking {
            metricsCollector.reset()
        }
    }
    
    @Test
    fun `test metrics collection and export`() = runTest {
        // 测试计数器
        metricsCollector.incrementCounter("test.counter", 5)
        metricsCollector.incrementCounter("test.counter", 3)
        
        assertEquals(8L, metricsCollector.getCounter("test.counter"))
        
        // 测试仪表盘
        metricsCollector.setGauge("test.gauge", 42.5)
        assertEquals(42.5, metricsCollector.getGauge("test.gauge"), 0.01)
        
        // 测试直方图
        repeat(100) { i ->
            metricsCollector.recordHistogram("test.histogram", i.toDouble())
        }
        
        val histogramStats = metricsCollector.getHistogramStats("test.histogram")
        assertNotNull(histogramStats)
        assertEquals(100, histogramStats!!.count)
        assertTrue(histogramStats.mean > 40.0 && histogramStats.mean < 60.0)
        
        // 测试计时器
        val timer = metricsCollector.startTimer("test.timer")
        delay(50)
        timer.stop()
        
        val timerStats = metricsCollector.getTimerStats("test.timer")
        assertNotNull(timerStats)
        assertEquals(1, timerStats!!.count)
        assertTrue(timerStats.mean >= 40.0) // 至少40ms
        
        // 测试带标签的指标
        metricsCollector.incrementCounter("test.tagged", tags = mapOf("env" to "test", "service" to "disruptorx"))
        assertEquals(1L, metricsCollector.getCounter("test.tagged", mapOf("env" to "test", "service" to "disruptorx")))
        
        // 获取所有指标快照
        val snapshot = metricsCollector.getAllMetrics()
        assertTrue(snapshot.counters.isNotEmpty())
        assertTrue(snapshot.gauges.isNotEmpty())
        assertTrue(snapshot.histograms.isNotEmpty())
        assertTrue(snapshot.timers.isNotEmpty())
    }
    
    @Test
    fun `test distributed lock service`() = runTest {
        // 测试锁获取
        println("开始测试锁获取...")
        val lock1 = lockService.tryLock("test-resource", timeout = 5.seconds)
        println("锁获取结果: $lock1")
        assertNotNull(lock1)
        assertEquals("test-resource", lock1!!.lockName)
        assertEquals("node1", lock1.nodeId)
        
        // 测试锁冲突
        val lock2 = lockService.tryLock("test-resource", timeout = 100.milliseconds)
        assertNull(lock2) // 应该获取失败，因为资源已被锁定
        
        // 测试锁释放
        val unlockResult = lockService.unlock(lock1)
        assertTrue(unlockResult)
        
        // 释放后应该能重新获取
        val lock3 = lockService.tryLock("test-resource", timeout = 1.seconds)
        assertNotNull(lock3)
        
        // 清理
        if (lock3 != null) {
            lockService.unlock(lock3)
        }
    }
    
    @Test
    fun `test workflow management`() = runTest {
        // 创建简单的工作流定义
        val workflowDef = WorkflowDefinition(
            id = "test-workflow",
            name = "Test Workflow",
            description = "A simple test workflow",
            version = "1.0",
            steps = listOf(
                WorkflowStep(
                    name = "step1",
                    type = StepType.TASK,
                    handler = "test-handler",
                    input = mapOf("message" to "Hello")
                ),
                WorkflowStep(
                    name = "step2",
                    type = StepType.WAIT,
                    handler = "wait-handler",
                    input = mapOf("duration" to 100L)
                ),
                WorkflowStep(
                    name = "step3",
                    type = StepType.TASK,
                    handler = "test-handler",
                    input = mapOf("message" to "World")
                )
            ),
            timeout = 30.seconds
        )
        
        // 注册工作流
        workflowManager.registerWorkflow(workflowDef)
        
        // 启动工作流实例
        val instanceId = workflowManager.startWorkflow(
            "test-workflow",
            input = mapOf("userId" to "12345")
        )
        
        assertNotNull(instanceId)
        assertTrue(instanceId.startsWith("wf_"))
        
        // 检查工作流状态
        val instance = workflowManager.getWorkflowStatus(instanceId)
        assertNotNull(instance)
        assertEquals(WorkflowStatus.RUNNING, instance!!.status)
        assertEquals("test-workflow", instance.workflowId)
        assertEquals(mapOf("userId" to "12345"), instance.input)
        
        // 测试暂停和恢复
        workflowManager.pauseWorkflow(instanceId)
        delay(50)
        
        val pausedInstance = workflowManager.getWorkflowStatus(instanceId)
        assertEquals(WorkflowStatus.PAUSED, pausedInstance!!.status)
        
        workflowManager.resumeWorkflow(instanceId)
        delay(50)
        
        val resumedInstance = workflowManager.getWorkflowStatus(instanceId)
        assertEquals(WorkflowStatus.RUNNING, resumedInstance!!.status)
        
        // 测试停止工作流
        workflowManager.stopWorkflow(instanceId, "Test completed")
        delay(50)
        
        val stoppedInstance = workflowManager.getWorkflowStatus(instanceId)
        assertEquals(WorkflowStatus.STOPPED, stoppedInstance!!.status)
        assertEquals("Test completed", stoppedInstance.error)
        
        // 验证指标
        assertTrue(metricsCollector.getCounter("workflow.registered") > 0)
        assertTrue(metricsCollector.getCounter("workflow.started") > 0)
        assertTrue(metricsCollector.getCounter("workflow.stopped") > 0)
    }
    
    @Test
    fun `test workflow with retry policy`() = runTest {
        // 创建带重试策略的工作流
        val workflowDef = WorkflowDefinition(
            id = "retry-workflow",
            name = "Retry Test Workflow",
            description = "Workflow with retry policy",
            version = "1.0",
            steps = listOf(
                WorkflowStep(
                    name = "failing-step",
                    type = StepType.TASK,
                    handler = "failing-handler",
                    retryPolicy = RetryPolicy(
                        maxRetries = 3,
                        delay = 100.milliseconds,
                        backoffMultiplier = 2.0
                    )
                )
            )
        )
        
        workflowManager.registerWorkflow(workflowDef)
        
        val instanceId = workflowManager.startWorkflow("retry-workflow")
        
        // 等待一段时间让工作流执行
        delay(500)
        
        val instance = workflowManager.getWorkflowStatus(instanceId)
        assertNotNull(instance)
        
        // 清理
        if (instance!!.status == WorkflowStatus.RUNNING) {
            workflowManager.stopWorkflow(instanceId)
        }
    }
    
    @Test
    fun `test concurrent workflow execution`() = runTest {
        // 创建简单工作流
        val workflowDef = WorkflowDefinition(
            id = "concurrent-workflow",
            name = "Concurrent Test Workflow",
            description = "Test concurrent execution",
            version = "1.0",
            steps = listOf(
                WorkflowStep(
                    name = "concurrent-step",
                    type = StepType.WAIT,
                    handler = "wait-handler",
                    input = mapOf("duration" to 200L)
                )
            )
        )
        
        workflowManager.registerWorkflow(workflowDef)
        
        // 并发启动多个工作流实例
        val instanceIds = mutableListOf<String>()
        
        repeat(5) { i ->
            val instanceId = workflowManager.startWorkflow(
                "concurrent-workflow",
                input = mapOf("index" to i)
            )
            instanceIds.add(instanceId)
        }
        
        assertEquals(5, instanceIds.size)
        
        // 检查所有实例都在运行
        instanceIds.forEach { instanceId ->
            val instance = workflowManager.getWorkflowStatus(instanceId)
            assertNotNull(instance)
            assertEquals(WorkflowStatus.RUNNING, instance!!.status)
        }
        
        // 等待执行完成
        delay(500)
        
        // 清理运行中的实例
        instanceIds.forEach { instanceId ->
            val instance = workflowManager.getWorkflowStatus(instanceId)
            if (instance?.status == WorkflowStatus.RUNNING) {
                workflowManager.stopWorkflow(instanceId)
            }
        }
        
        // 验证运行中的工作流数量
        val runningWorkflows = workflowManager.getRunningWorkflows()
        assertEquals(0, runningWorkflows.size)
    }
    
    @Test
    fun `test metrics with workflow integration`() = runTest {
        // 创建工作流
        val workflowDef = WorkflowDefinition(
            id = "metrics-workflow",
            name = "Metrics Test Workflow",
            description = "Test metrics integration",
            version = "1.0",
            steps = listOf(
                WorkflowStep(
                    name = "metrics-step",
                    type = StepType.WAIT,
                    handler = "wait-handler",
                    input = mapOf("duration" to 100L)
                )
            )
        )
        
        workflowManager.registerWorkflow(workflowDef)
        
        // 记录初始指标
        val initialRegistered = metricsCollector.getCounter("workflow.registered")
        val initialStarted = metricsCollector.getCounter("workflow.started")
        
        // 启动工作流
        val instanceId = workflowManager.startWorkflow("metrics-workflow")
        
        // 等待指标更新
        delay(100)
        
        // 验证指标增加
        assertTrue(metricsCollector.getCounter("workflow.registered") > initialRegistered)
        assertTrue(metricsCollector.getCounter("workflow.started") > initialStarted)
        
        // 检查仪表盘指标
        delay(200) // 等待监控协程更新指标
        
        val runningGauge = metricsCollector.getGauge("workflow.instances.running")
        assertTrue(runningGauge >= 0.0)
        
        // 清理
        workflowManager.stopWorkflow(instanceId)
        delay(100)
        
        // 验证停止指标
        assertTrue(metricsCollector.getCounter("workflow.stopped") > 0)
    }
    
    @Test
    fun `test system integration`() = runTest {
        // 综合测试：同时使用锁、工作流和指标
        
        // 1. 获取分布式锁
        val lock = lockService.tryLock("integration-resource", timeout = 5.seconds)
        assertNotNull(lock)
        
        // 2. 在锁保护下启动工作流
        val workflowDef = WorkflowDefinition(
            id = "integration-workflow",
            name = "Integration Test Workflow",
            description = "Test system integration",
            version = "1.0",
            steps = listOf(
                WorkflowStep(
                    name = "protected-step",
                    type = StepType.WAIT,
                    handler = "wait-handler",
                    input = mapOf("duration" to 150L)
                )
            )
        )
        
        workflowManager.registerWorkflow(workflowDef)
        val instanceId = workflowManager.startWorkflow("integration-workflow")
        
        // 3. 记录自定义指标
        metricsCollector.incrementCounter("integration.test.started")
        
        // 4. 等待工作流执行
        delay(200)
        
        // 5. 验证状态
        val instance = workflowManager.getWorkflowStatus(instanceId)
        assertNotNull(instance)
        
        val integrationCounter = metricsCollector.getCounter("integration.test.started")
        assertEquals(1L, integrationCounter)
        
        // 6. 清理资源
        workflowManager.stopWorkflow(instanceId)
        lockService.unlock(lock!!)
        
        // 7. 验证最终状态
        delay(100)
        val finalInstance = workflowManager.getWorkflowStatus(instanceId)
        assertEquals(WorkflowStatus.STOPPED, finalInstance!!.status)
        
        // 8. 获取完整的指标快照
        val snapshot = metricsCollector.getAllMetrics()
        assertTrue(snapshot.counters.containsKey("integration.test.started"))
        assertTrue(snapshot.counters.containsKey("workflow.registered"))
        assertTrue(snapshot.counters.containsKey("workflow.started"))
        assertTrue(snapshot.counters.containsKey("workflow.stopped"))
    }
}