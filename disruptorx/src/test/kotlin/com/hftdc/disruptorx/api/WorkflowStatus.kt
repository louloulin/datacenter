package com.hftdc.disruptorx.api

/**
 * 工作流状态枚举
 * 表示工作流的当前运行状态
 */
enum class WorkflowStatus {
    /**
     * 工作流已创建但尚未启动
     */
    CREATED,
    
    /**
     * 工作流正在启动中
     */
    STARTING,
    
    /**
     * 工作流正在运行
     */
    RUNNING,
    
    /**
     * 工作流正在停止中
     */
    STOPPING,
    
    /**
     * 工作流已停止
     */
    STOPPED,
    
    /**
     * 工作流处于错误状态
     */
    ERROR,
    
    /**
     * 工作流状态未知
     */
    UNKNOWN
} 