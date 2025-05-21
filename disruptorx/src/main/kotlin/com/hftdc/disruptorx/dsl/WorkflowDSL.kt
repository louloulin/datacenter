package com.hftdc.disruptorx.dsl

import com.hftdc.disruptorx.api.EventHandler
import com.hftdc.disruptorx.api.PartitionStrategy
import com.hftdc.disruptorx.api.Workflow
import com.hftdc.disruptorx.api.WorkflowSink
import com.hftdc.disruptorx.api.WorkflowSource
import com.hftdc.disruptorx.api.WorkflowStage

/**
 * 工作流定义DSL
 * 提供基于Kotlin DSL的流程定义语法
 */
class WorkflowDSL(
    private val id: String,
    private val name: String
) {
    private var sourceBlock: WorkflowSourceBlock? = null
    private val stageBlocks = mutableListOf<WorkflowStageBlock>()
    private var sinkBlock: WorkflowSinkBlock? = null
    
    /**
     * 定义工作流输入源
     */
    fun source(init: WorkflowSourceBlock.() -> Unit) {
        val sourceBlock = WorkflowSourceBlock()
        sourceBlock.init()
        this.sourceBlock = sourceBlock
    }
    
    /**
     * 定义工作流处理阶段
     */
    fun stages(init: WorkflowStagesBlock.() -> Unit) {
        val stagesBlock = WorkflowStagesBlock()
        stagesBlock.init()
        this.stageBlocks.addAll(stagesBlock.stages)
    }
    
    /**
     * 定义工作流输出
     */
    fun sink(init: WorkflowSinkBlock.() -> Unit) {
        val sinkBlock = WorkflowSinkBlock()
        sinkBlock.init()
        this.sinkBlock = sinkBlock
    }
    
    /**
     * 构建工作流定义
     */
    internal fun build(): Workflow {
        val source = sourceBlock?.build() ?: throw IllegalStateException("Workflow source not defined")
        val sink = sinkBlock?.build() ?: throw IllegalStateException("Workflow sink not defined")
        
        return WorkflowImpl(
            id = id,
            name = name,
            source = source,
            stages = stageBlocks.map { it.build() },
            sink = sink
        )
    }
}

/**
 * 工作流来源块
 */
class WorkflowSourceBlock {
    private var topic: String = ""
    private var partitionStrategy: PartitionStrategy = PartitionStrategy.CONSISTENT_HASH
    private var partitionByBlock: ((Any) -> Any)? = null
    
    /**
     * 设置来源主题
     */
    fun fromTopic(topic: String) {
        this.topic = topic
    }
    
    /**
     * 设置分区策略
     */
    fun partitionBy(extractor: (Any) -> Any) {
        this.partitionByBlock = extractor
    }
    
    /**
     * 构建工作流来源
     */
    internal fun build(): WorkflowSource {
        if (topic.isEmpty()) {
            throw IllegalStateException("Source topic not defined")
        }
        
        return WorkflowSourceImpl(
            topic = topic,
            partitionStrategy = partitionStrategy
        )
    }
}

/**
 * 工作流阶段集合块
 */
class WorkflowStagesBlock {
    val stages = mutableListOf<WorkflowStageBlock>()
    
    /**
     * 定义单个处理阶段
     */
    fun stage(id: String, init: WorkflowStageBlock.() -> Unit) {
        val stage = WorkflowStageBlock(id)
        stage.init()
        stages.add(stage)
    }
}

/**
 * 工作流阶段块
 */
class WorkflowStageBlock(private val id: String) {
    var parallelism: Int = 1
    private var handlerBlock: ((Any) -> Unit)? = null
    
    /**
     * 设置事件处理器
     */
    fun handler(handler: (Any) -> Unit) {
        this.handlerBlock = handler
    }
    
    /**
     * 构建工作流阶段
     */
    internal fun build(): WorkflowStage {
        val handler = handlerBlock ?: throw IllegalStateException("Stage handler not defined for stage: $id")
        
        return WorkflowStageImpl(
            id = id,
            parallelism = parallelism,
            handlerFunction = handler
        )
    }
}

/**
 * 工作流输出块
 */
class WorkflowSinkBlock {
    private var topic: String = ""
    
    /**
     * 设置输出主题
     */
    fun toTopic(topic: String) {
        this.topic = topic
    }
    
    /**
     * 构建工作流输出
     */
    internal fun build(): WorkflowSink {
        if (topic.isEmpty()) {
            throw IllegalStateException("Sink topic not defined")
        }
        
        return WorkflowSinkImpl(topic = topic)
    }
}

/**
 * 工作流来源实现
 */
internal class WorkflowSourceImpl(
    override val topic: String,
    override val partitionStrategy: PartitionStrategy
) : WorkflowSource

/**
 * 工作流阶段实现
 */
internal class WorkflowStageImpl(
    override val id: String,
    override val parallelism: Int,
    private val handlerFunction: (Any) -> Unit
) : WorkflowStage {
    
    override fun getHandler(): EventHandler<Any> {
        return object : EventHandler<Any> {
            override fun onEvent(event: Any, sequence: Long, endOfBatch: Boolean) {
                handlerFunction(event)
            }
        }
    }
}

/**
 * 工作流输出实现
 */
internal class WorkflowSinkImpl(
    override val topic: String
) : WorkflowSink

/**
 * 工作流实现
 */
internal class WorkflowImpl(
    override val id: String,
    override val name: String,
    override val source: WorkflowSource,
    override val stages: List<WorkflowStage>,
    override val sink: WorkflowSink
) : Workflow

/**
 * 工作流DSL构建函数
 */
fun workflow(id: String, name: String = id, init: WorkflowDSL.() -> Unit): Workflow {
    val dsl = WorkflowDSL(id, name)
    dsl.init()
    return dsl.build()
} 