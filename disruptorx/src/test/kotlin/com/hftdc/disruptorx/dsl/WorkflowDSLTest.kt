package com.hftdc.disruptorx.dsl

import com.hftdc.disruptorx.api.PartitionStrategy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkflowDSLTest {

    @Test
    fun `should create workflow with valid definition`() {
        val workflow = workflow("testWorkflow", "Test Workflow") {
            source {
                fromTopic("input-topic")
                partitionBy { it }
            }
            
            stages {
                stage("stage1") {
                    handler { event ->
                        // 简单的处理逻辑
                        println("Processing: $event")
                    }
                }
                
                stage("stage2") {
                    parallelism = 2
                    handler { event ->
                        // 另一个处理阶段
                        println("Second stage: $event")
                    }
                }
            }
            
            sink {
                toTopic("output-topic")
            }
        }
        
        // 验证工作流基本属性
        assertEquals("testWorkflow", workflow.id)
        assertEquals("Test Workflow", workflow.name)
        
        // 验证来源配置
        assertEquals("input-topic", workflow.source.topic)
        assertEquals(PartitionStrategy.CONSISTENT_HASH, workflow.source.partitionStrategy)
        
        // 验证处理阶段
        assertEquals(2, workflow.stages.size)
        assertEquals("stage1", workflow.stages[0].id)
        assertEquals(1, workflow.stages[0].parallelism) // 默认并行度
        assertEquals("stage2", workflow.stages[1].id)
        assertEquals(2, workflow.stages[1].parallelism) // 自定义并行度
        
        // 验证输出配置
        assertEquals("output-topic", workflow.sink.topic)
    }
    
    @Test
    fun `should throw exception when source not defined`() {
        val exception = assertThrows<IllegalStateException> {
            workflow("missingSource", "Missing Source") {
                stages {
                    stage("stage1") {
                        handler { _ -> }
                    }
                }
                
                sink {
                    toTopic("output-topic")
                }
            }
        }
        
        assertTrue(exception.message?.contains("source not defined") ?: false)
    }
    
    @Test
    fun `should throw exception when sink not defined`() {
        val exception = assertThrows<IllegalStateException> {
            workflow("missingSink", "Missing Sink") {
                source {
                    fromTopic("input-topic")
                }
                
                stages {
                    stage("stage1") {
                        handler { _ -> }
                    }
                }
            }
        }
        
        assertTrue(exception.message?.contains("sink not defined") ?: false)
    }
    
    @Test
    fun `should throw exception when stage handler not defined`() {
        val exception = assertThrows<IllegalStateException> {
            workflow("missingHandler", "Missing Handler") {
                source {
                    fromTopic("input-topic")
                }
                
                stages {
                    stage("stage1") {
                        // 没有定义handler
                    }
                }
                
                sink {
                    toTopic("output-topic")
                }
            }
        }
        
        assertTrue(exception.message?.contains("handler not defined") ?: false)
    }
} 