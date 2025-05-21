package com.hftdc.disruptorx.distributed

import com.hftdc.disruptorx.api.NodeStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DistributedSequenceImplTest {

    private lateinit var nodeManager: NodeManagerImpl
    private lateinit var sequenceBroadcaster: SequenceBroadcaster
    private lateinit var localSequence: DistributedSequenceImpl
    private lateinit var remoteSequence: DistributedSequenceImpl
    
    private val localNodeId = "local-node-1"
    private val remoteNodeId = "remote-node-1"
    private val sequenceId = "test-sequence"
    
    @BeforeEach
    fun setup() {
        // 创建模拟依赖
        nodeManager = mockk(relaxed = true)
        sequenceBroadcaster = mockk(relaxed = true)
        
        // 配置nodeManager的行为
        every { nodeManager.getLocalNodeId() } returns localNodeId
        
        // 创建本地序列和远程序列实例
        localSequence = DistributedSequenceImpl(
            sequenceId = sequenceId,
            nodeManager = nodeManager,
            nodeId = localNodeId,
            initialValue = -1,
            sequenceBroadcaster = sequenceBroadcaster
        )
        
        remoteSequence = DistributedSequenceImpl(
            sequenceId = sequenceId,
            nodeManager = nodeManager,
            nodeId = remoteNodeId,
            initialValue = -1,
            sequenceBroadcaster = sequenceBroadcaster
        )
    }
    
    @Test
    fun `local sequence should correctly identify itself as local`() {
        assertEquals(true, localSequence.isLocal())
        assertEquals(false, remoteSequence.isLocal())
    }
    
    @Test
    fun `get should return current sequence value`() {
        // 初始值应该是-1
        assertEquals(-1, localSequence.get())
        
        // 设置一个新值
        localSequence.set(10)
        assertEquals(10, localSequence.get())
    }
    
    @Test
    fun `incrementAndGet should increment the value and return the new value`() {
        // 首先设置初始值
        localSequence.set(5)
        
        // 递增并获取
        val newValue = localSequence.incrementAndGet()
        
        // 验证返回值和当前值
        assertEquals(6, newValue)
        assertEquals(6, localSequence.get())
    }
    
    @Test
    fun `addAndGet should add the increment and return the new value`() {
        // 首先设置初始值
        localSequence.set(5)
        
        // 添加增量并获取
        val newValue = localSequence.addAndGet(15)
        
        // 验证返回值和当前值
        assertEquals(20, newValue)
        assertEquals(20, localSequence.get())
    }
    
    @Test
    fun `local sequence changes above threshold should trigger broadcast`() = runBlocking {
        // 设置初始值
        localSequence.set(0)
        
        // 大增量变化应触发广播
        localSequence.addAndGet(100)
        
        // 验证广播被调用
        coVerify(timeout = 1000) { 
            sequenceBroadcaster.broadcastSequenceValue(sequenceId, 100) 
        }
    }
    
    @Test
    fun `remote sequence changes should not trigger broadcast`() = runBlocking {
        // 设置远程序列的值
        remoteSequence.set(100)
        
        // 验证广播未被调用
        coVerify(exactly = 0) { 
            sequenceBroadcaster.broadcastSequenceValue(any(), any()) 
        }
    }
    
    @Test
    fun `updateRemoteValue should only update if new value is greater`() {
        // 设置初始值
        remoteSequence.set(10)
        
        // 更新为较小的值，不应发生变化
        remoteSequence.updateRemoteValue(5)
        assertEquals(10, remoteSequence.get())
        
        // 更新为较大的值，应更新成功
        remoteSequence.updateRemoteValue(15)
        assertEquals(15, remoteSequence.get())
    }
} 