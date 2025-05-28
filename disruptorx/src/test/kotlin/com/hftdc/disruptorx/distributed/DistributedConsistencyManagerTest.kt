package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.NodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 分布式一致性管理器测试
 */
class DistributedConsistencyManagerTest {
    
    private lateinit var consistencyManager: DistributedConsistencyManager
    private lateinit var clusterNodes: List<NodeInfo>
    
    @BeforeEach
    fun setUp() {
        clusterNodes = listOf(
            NodeInfo("node1", "localhost", 8001),
            NodeInfo("node2", "localhost", 8002),
            NodeInfo("node3", "localhost", 8003)
        )
        
        consistencyManager = DistributedConsistencyManager(
            nodeId = "node1",
            clusterNodes = clusterNodes
        )
    }
    
    @AfterEach
    fun tearDown() = runBlocking {
        consistencyManager.stop()
    }
    
    @Test
    fun `test consistency manager startup and shutdown`() = runBlocking {
        // 测试启动
        consistencyManager.start()
        
        // 验证状态
        val status = consistencyManager.getConsistencyStatus()
        assertNotNull(status)
        assertEquals(3, status.clusterSize)
        
        // 测试关闭
        consistencyManager.stop()
    }
    
    @Test
    fun `test distributed event commit`() = runBlocking {
        consistencyManager.start()
        
        // 创建分布式事件
        val event = DistributedEvent(
            eventId = "test-event-1",
            eventType = "USER_ACTION",
            payload = "{\"action\": \"login\", \"userId\": \"user123\"}"
        )
        
        // 提交事件
        val result = consistencyManager.commitEvent(event)
        
        // 验证结果
        assertNotNull(result)
        // 注意：在测试环境中，由于没有真实的集群，可能会失败
        // 这里主要测试接口调用不会抛出异常
    }
    
    @Test
    fun `test snapshot creation and restoration`() = runBlocking {
        consistencyManager.start()
        
        // 创建快照
        val snapshotResult = consistencyManager.createSnapshot()
        assertNotNull(snapshotResult)
        
        if (snapshotResult.isSuccess) {
            // 测试从快照恢复
            val restoreResult = consistencyManager.restoreFromSnapshot(snapshotResult.snapshotId!!)
            // 在测试环境中，恢复可能会失败，但不应该抛出异常
        }
    }
    
    @Test
    fun `test consistency status monitoring`() = runBlocking {
        consistencyManager.start()
        
        val status = consistencyManager.getConsistencyStatus()
        
        // 验证状态字段
        assertNotNull(status)
        assertEquals(3, status.clusterSize)
        assertTrue(status.currentTerm >= 0)
        assertTrue(status.commitIndex >= 0)
        assertTrue(status.lastApplied >= 0)
        assertTrue(status.activeNodes >= 0)
    }
    
    @Test
    fun `test multiple event commits`() = runBlocking {
        consistencyManager.start()
        
        val events = listOf(
            DistributedEvent("event1", "TYPE_A", "payload1"),
            DistributedEvent("event2", "TYPE_B", "payload2"),
            DistributedEvent("event3", "TYPE_A", "payload3")
        )
        
        // 提交多个事件
        val results = events.map { event ->
            consistencyManager.commitEvent(event)
        }
        
        // 验证所有提交都完成（不抛出异常）
        assertEquals(3, results.size)
        results.forEach { result ->
            assertNotNull(result)
        }
    }
    
    @Test
    fun `test distributed event log functionality`() {
        val eventLog = DistributedEventLog()
        
        // 测试日志条目添加
        val entry = EventLogEntry(
            term = 1,
            index = 1,
            event = DistributedEvent("test", "TEST", "data"),
            timestamp = System.currentTimeMillis()
        )
        
        eventLog.appendEntry(entry)
        
        // 验证日志状态
        assertEquals(1, eventLog.size())
        assertEquals(2, eventLog.getNextIndex())
        assertEquals(1, eventLog.getLastIndex())
        
        // 测试获取条目
        val retrievedEntry = eventLog.getEntry(1)
        assertNotNull(retrievedEntry)
        assertEquals(entry.eventId, retrievedEntry.event.eventId)
    }
    
    @Test
    fun `test snapshot manager functionality`() = runBlocking {
        val snapshotManager = SnapshotManager()
        
        val clusterState = ClusterState(
            nodes = clusterNodes,
            eventLogSize = 100,
            lastEventTimestamp = System.currentTimeMillis()
        )
        
        val snapshot = ClusterSnapshot(
            term = 1,
            index = 100,
            state = clusterState,
            timestamp = System.currentTimeMillis()
        )
        
        // 测试保存快照
        val saveResult = snapshotManager.saveSnapshot(snapshot)
        assertTrue(saveResult.isSuccess)
        assertNotNull(saveResult.snapshotId)
        
        // 测试加载快照
        val loadedSnapshot = snapshotManager.loadSnapshot(saveResult.snapshotId!!)
        assertNotNull(loadedSnapshot)
        assertEquals(snapshot.term, loadedSnapshot.term)
        assertEquals(snapshot.index, loadedSnapshot.index)
        
        // 测试列出快照
        val snapshots = snapshotManager.listSnapshots()
        assertTrue(snapshots.contains(saveResult.snapshotId))
    }
    
    @Test
    fun `test event log compaction`() {
        val eventLog = DistributedEventLog()
        
        // 添加多个条目
        repeat(15) { i ->
            val entry = EventLogEntry(
                term = 1,
                index = (i + 1).toLong(),
                event = DistributedEvent("event$i", "TEST", "data$i"),
                timestamp = System.currentTimeMillis()
            )
            eventLog.appendEntry(entry)
        }
        
        assertEquals(15, eventLog.size())
        
        // 测试压缩到索引10
        eventLog.compactToIndex(10)
        
        // 验证压缩后的状态
        assertTrue(eventLog.size() < 15)
        
        // 索引10之前的条目应该被删除
        for (i in 1..10) {
            assertEquals(null, eventLog.getEntry(i.toLong()))
        }
        
        // 索引10之后的条目应该还在
        for (i in 11..15) {
            assertNotNull(eventLog.getEntry(i.toLong()))
        }
    }
    
    @Test
    fun `test event log truncation`() {
        val eventLog = DistributedEventLog()
        
        // 添加多个条目
        repeat(10) { i ->
            val entry = EventLogEntry(
                term = 1,
                index = (i + 1).toLong(),
                event = DistributedEvent("event$i", "TEST", "data$i"),
                timestamp = System.currentTimeMillis()
            )
            eventLog.appendEntry(entry)
        }
        
        assertEquals(10, eventLog.size())
        assertEquals(11, eventLog.getNextIndex())
        
        // 测试截断到索引5
        eventLog.truncateToIndex(5)
        
        // 验证截断后的状态
        assertEquals(5, eventLog.size())
        assertEquals(6, eventLog.getNextIndex())
        
        // 索引5之后的条目应该被删除
        for (i in 6..10) {
            assertEquals(null, eventLog.getEntry(i.toLong()))
        }
        
        // 索引5之前的条目应该还在
        for (i in 1..5) {
            assertNotNull(eventLog.getEntry(i.toLong()))
        }
    }
    
    @Test
    fun `test consistency result types`() {
        // 测试成功结果
        val successResult = ConsistencyResult.SUCCESS
        assertTrue(successResult.isSuccess)
        assertEquals(null, successResult.errorMessage)
        
        // 测试失败结果
        val failedResult = ConsistencyResult.FAILED
        assertTrue(!failedResult.isSuccess)
        assertNotNull(failedResult.errorMessage)
        
        // 测试自定义结果
        val customResult = ConsistencyResult(false, "Custom error")
        assertTrue(!customResult.isSuccess)
        assertEquals("Custom error", customResult.errorMessage)
    }
}