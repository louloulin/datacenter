package com.hftdc.disruptorx.api

/**
 * 工作流接口
 * 定义数据处理管道和阶段
 */
interface Workflow {
    /**
     * 工作流ID
     */
    val id: String
    
    /**
     * 工作流名称
     */
    val name: String
    
    /**
     * 工作流来源
     */
    val source: WorkflowSource
    
    /**
     * 工作流处理阶段
     */
    val stages: List<WorkflowStage>
    
    /**
     * 工作流输出
     */
    val sink: WorkflowSink
}

/**
 * 工作流来源接口
 */
interface WorkflowSource {
    /**
     * 数据来源主题
     */
    val topic: String
}

/**
 * 工作流输出接口
 */
interface WorkflowSink {
    /**
     * 输出主题
     */
    val topic: String
}

/**
 * 工作流阶段接口
 */
interface WorkflowStage {
    /**
     * 阶段ID
     */
    val id: String
    
    /**
     * 并行度
     */
    val parallelism: Int
    
    /**
     * 获取阶段处理器
     */
    fun getHandler(): EventHandler<Any>
}

/**
 * 事件处理器接口
 */
interface EventHandler<T> {
    /**
     * 处理事件
     */
    fun onEvent(event: T, sequence: Long, endOfBatch: Boolean)
} 