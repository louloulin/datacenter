package com.hftdc.disruptorx.api

/**
 * 节点角色枚举
 * 定义节点在分布式系统中的角色
 */
enum class NodeRole {
    /**
     * 协调器节点
     * 负责集群协调和管理
     */
    COORDINATOR,
    
    /**
     * 工作节点
     * 执行实际的数据处理任务
     */
    WORKER
} 