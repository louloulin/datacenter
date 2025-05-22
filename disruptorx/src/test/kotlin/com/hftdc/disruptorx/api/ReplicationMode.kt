package com.hftdc.disruptorx.api

/**
 * 复制模式枚举
 * 定义事件总线如何在节点间复制消息
 */
enum class ReplicationMode {
    /**
     * 同步复制模式
     * 消息发布者等待消息在所有节点上确认后才返回
     */
    SYNC,
    
    /**
     * 异步复制模式
     * 消息发布者在本地节点确认后立即返回，不等待其他节点
     */
    ASYNC,
    
    /**
     * 混合复制模式
     * 消息发布者等待消息在大多数节点上确认后返回
     */
    MIXED
} 