package com.hftdc.disruptorx.monitoring

import com.hftdc.disruptorx.security.SecurityManager
import com.hftdc.disruptorx.security.UserRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class RealTimeMonitoringDashboardTest {

    private lateinit var dashboard: RealTimeMonitoringDashboard
    private lateinit var metricsCollector: TradingMetricsCollector
    private lateinit var securityManager: SecurityManager
    private lateinit var userToken: String

    @BeforeEach
    fun setup() = runBlocking {
        metricsCollector = TradingMetricsCollector()
        securityManager = SecurityManager("test-node")
        
        dashboard = RealTimeMonitoringDashboard(
            nodeId = "test-node",
            metricsCollector = metricsCollector,
            securityManager = securityManager,
            config = DashboardConfig(updateInterval = 1.seconds)
        )
        
        // 创建测试用户
        val user = securityManager.createUser("testuser", "password123", UserRole.ADMIN)
        val authToken = securityManager.authenticate("testuser", "password123")!!
        userToken = authToken.tokenId
    }

    @AfterEach
    fun cleanup() {
        dashboard.stop()
    }

    @Test
    fun `测试仪表板启动和停止`() {
        dashboard.start()
        
        val status = dashboard.getDashboardStatus()
        assertTrue(status["isRunning"] as Boolean, "仪表板应该正在运行")
        assertEquals("test-node", status["nodeId"], "节点ID应该匹配")
        
        dashboard.stop()
        
        val statusAfterStop = dashboard.getDashboardStatus()
        assertFalse(statusAfterStop["isRunning"] as Boolean, "仪表板应该已停止")
        
        println("✅ 仪表板启动和停止测试通过")
    }

    @Test
    fun `测试实时指标收集`() = runBlocking {
        dashboard.start()
        
        // 模拟一些性能数据
        dashboard.recordLatency(50.0)
        dashboard.recordLatency(75.0)
        dashboard.recordLatency(100.0)
        dashboard.recordThroughput(1000.0)
        dashboard.recordRequest()
        dashboard.recordRequest()
        dashboard.recordError()
        
        // 等待指标收集
        delay(2.seconds)
        
        val metrics = dashboard.getCurrentMetrics(userToken)
        assertNotNull(metrics, "应该能获取到当前指标")
        
        println("实时指标:")
        println("  节点ID: ${metrics!!.nodeId}")
        println("  时间戳: ${metrics.timestamp}")
        println("  CPU使用率: ${String.format("%.1f", metrics.systemMetrics.cpuUsage)}%")
        println("  内存使用率: ${String.format("%.1f", metrics.systemMetrics.memoryUsage)}%")
        println("  平均延迟: ${String.format("%.1f", metrics.performanceMetrics.averageLatency)} μs")
        println("  吞吐量: ${String.format("%.1f", metrics.performanceMetrics.throughput)}")
        println("  错误率: ${String.format("%.1f", metrics.performanceMetrics.errorRate)}%")
        
        assertEquals("test-node", metrics.nodeId, "节点ID应该匹配")
        assertTrue(metrics.timestamp > 0, "时间戳应该有效")
        assertTrue(metrics.systemMetrics.cpuUsage >= 0, "CPU使用率应该非负")
        assertTrue(metrics.systemMetrics.memoryUsage >= 0, "内存使用率应该非负")
        
        println("✅ 实时指标收集测试通过")
    }

    @Test
    fun `测试告警功能`() = runBlocking {
        var alertReceived = false
        var receivedAlert: MonitoringAlert? = null
        
        // 添加告警监听器
        dashboard.addListener(object : MonitoringEventListener {
            override suspend fun onEvent(event: MonitoringEvent) {
                if (event is MonitoringEvent.AlertTriggered) {
                    alertReceived = true
                    receivedAlert = event.alert
                }
            }
        })
        
        dashboard.start()
        
        // 模拟高延迟触发告警
        repeat(10) {
            dashboard.recordLatency(2000.0) // 2000微秒，超过阈值
        }
        
        // 等待告警处理
        delay(3.seconds)
        
        val activeAlerts = dashboard.getActiveAlerts(userToken)
        
        println("告警测试结果:")
        println("  收到告警: $alertReceived")
        println("  活跃告警数量: ${activeAlerts.size}")
        
        if (receivedAlert != null) {
            println("  告警详情:")
            println("    ID: ${receivedAlert!!.id}")
            println("    级别: ${receivedAlert!!.level}")
            println("    标题: ${receivedAlert!!.title}")
            println("    消息: ${receivedAlert!!.message}")
            println("    指标: ${receivedAlert!!.metric}")
            println("    值: ${receivedAlert!!.value}")
            println("    阈值: ${receivedAlert!!.threshold}")
        }
        
        assertTrue(activeAlerts.isNotEmpty(), "应该有活跃告警")
        
        // 测试告警确认
        if (activeAlerts.isNotEmpty()) {
            val alertId = activeAlerts.first().id
            val acknowledged = dashboard.acknowledgeAlert(alertId, userToken)
            assertTrue(acknowledged, "应该能成功确认告警")
            
            val alertsAfterAck = dashboard.getActiveAlerts(userToken)
            assertTrue(alertsAfterAck.size < activeAlerts.size, "确认后告警数量应该减少")
        }
        
        println("✅ 告警功能测试通过")
    }

    @Test
    fun `测试历史指标查询`() = runBlocking {
        dashboard.start()
        
        val startTime = System.currentTimeMillis()
        
        // 生成一些历史数据
        repeat(5) { i ->
            dashboard.recordLatency((50 + i * 10).toDouble())
            dashboard.recordThroughput((1000 + i * 100).toDouble())
            delay(1.seconds)
        }
        
        val endTime = System.currentTimeMillis()
        
        // 查询历史指标
        val historicalMetrics = dashboard.getHistoricalMetrics(userToken, startTime, endTime)
        
        println("历史指标查询结果:")
        println("  查询时间范围: ${endTime - startTime} ms")
        println("  历史记录数量: ${historicalMetrics.size}")
        
        assertTrue(historicalMetrics.isNotEmpty(), "应该有历史指标数据")
        
        // 验证时间排序
        if (historicalMetrics.size > 1) {
            for (i in 1 until historicalMetrics.size) {
                assertTrue(
                    historicalMetrics[i].timestamp >= historicalMetrics[i-1].timestamp,
                    "历史指标应该按时间排序"
                )
            }
        }
        
        println("✅ 历史指标查询测试通过")
    }

    @Test
    fun `测试权限控制`() = runBlocking {
        dashboard.start()
        
        // 创建无权限用户
        val limitedUser = securityManager.createUser("limiteduser", "password123", UserRole.VIEWER)
        val limitedToken = securityManager.authenticate("limiteduser", "password123")!!
        
        // 测试读取权限
        try {
            dashboard.getCurrentMetrics(limitedToken.tokenId)
            // VIEWER角色应该有读取权限，所以不应该抛出异常
        } catch (e: SecurityException) {
            fail("VIEWER角色应该有读取指标的权限")
        }
        
        // 测试写入权限（确认告警）
        assertThrows(SecurityException::class.java) {
            runBlocking {
                dashboard.acknowledgeAlert("test-alert", limitedToken.tokenId)
            }
        }
        
        println("✅ 权限控制测试通过")
    }

    @Test
    fun `测试性能指标计算`() = runBlocking {
        dashboard.start()
        
        // 记录一系列延迟数据
        val latencies = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0)
        latencies.forEach { latency ->
            dashboard.recordLatency(latency)
        }
        
        // 记录吞吐量数据
        repeat(10) {
            dashboard.recordThroughput(100.0)
        }
        
        // 记录请求和错误
        repeat(100) {
            dashboard.recordRequest()
        }
        repeat(5) {
            dashboard.recordError()
        }
        
        delay(2.seconds)
        
        val metrics = dashboard.getCurrentMetrics(userToken)
        assertNotNull(metrics)
        
        println("性能指标计算结果:")
        println("  平均延迟: ${String.format("%.1f", metrics!!.performanceMetrics.averageLatency)} μs")
        println("  P95延迟: ${String.format("%.1f", metrics.performanceMetrics.p95Latency)} μs")
        println("  P99延迟: ${String.format("%.1f", metrics.performanceMetrics.p99Latency)} μs")
        println("  吞吐量: ${String.format("%.1f", metrics.performanceMetrics.throughput)}")
        println("  错误率: ${String.format("%.1f", metrics.performanceMetrics.errorRate)}%")
        
        // 验证计算结果
        assertTrue(metrics.performanceMetrics.averageLatency > 0, "平均延迟应该大于0")
        assertTrue(metrics.performanceMetrics.p95Latency >= metrics.performanceMetrics.averageLatency, 
                  "P95延迟应该大于等于平均延迟")
        assertTrue(metrics.performanceMetrics.p99Latency >= metrics.performanceMetrics.p95Latency, 
                  "P99延迟应该大于等于P95延迟")
        assertTrue(metrics.performanceMetrics.errorRate == 5.0, "错误率应该是5%")
        
        println("✅ 性能指标计算测试通过")
    }

    @Test
    fun `测试监控事件监听器`() = runBlocking {
        var metricsUpdateCount = 0
        var alertCount = 0
        var thresholdExceededCount = 0
        
        dashboard.addListener(object : MonitoringEventListener {
            override suspend fun onEvent(event: MonitoringEvent) {
                when (event) {
                    is MonitoringEvent.MetricsUpdated -> metricsUpdateCount++
                    is MonitoringEvent.AlertTriggered -> alertCount++
                    is MonitoringEvent.ThresholdExceeded -> thresholdExceededCount++
                }
            }
        })
        
        dashboard.start()
        
        // 生成一些数据
        dashboard.recordLatency(50.0)
        dashboard.recordThroughput(1000.0)
        
        // 触发告警
        dashboard.recordLatency(2000.0) // 高延迟
        
        delay(3.seconds)
        
        println("监控事件统计:")
        println("  指标更新事件: $metricsUpdateCount")
        println("  告警事件: $alertCount")
        println("  阈值超出事件: $thresholdExceededCount")
        
        assertTrue(metricsUpdateCount > 0, "应该收到指标更新事件")
        
        println("✅ 监控事件监听器测试通过")
    }

    @Test
    fun `测试仪表板状态信息`() {
        val status = dashboard.getDashboardStatus()
        
        println("仪表板状态:")
        status.forEach { (key, value) ->
            println("  $key: $value")
        }
        
        assertTrue(status.containsKey("nodeId"), "状态应该包含节点ID")
        assertTrue(status.containsKey("isRunning"), "状态应该包含运行状态")
        assertTrue(status.containsKey("metricsCount"), "状态应该包含指标数量")
        assertTrue(status.containsKey("activeAlertsCount"), "状态应该包含活跃告警数量")
        assertTrue(status.containsKey("updateInterval"), "状态应该包含更新间隔")
        assertTrue(status.containsKey("lastUpdateTime"), "状态应该包含最后更新时间")
        
        assertEquals("test-node", status["nodeId"], "节点ID应该匹配")
        assertEquals(false, status["isRunning"], "初始状态应该是未运行")
        
        println("✅ 仪表板状态信息测试通过")
    }
}
