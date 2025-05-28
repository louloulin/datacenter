package com.hftdc.disruptorx.example

import com.hftdc.disruptorx.DisruptorX
import com.hftdc.disruptorx.DisruptorXConfig
import com.hftdc.disruptorx.api.NodeRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * DisruptorX 基础示例
 * 演示如何创建节点、发布和订阅事件
 */
fun main() = runBlocking {
    println("=== DisruptorX 基础功能验证 ===")
    
    // 创建节点配置
    val config = DisruptorXConfig(
        nodeId = "example-node-1",
        host = "localhost",
        port = 8080,
        nodeRole = NodeRole.MIXED
    )
    
    // 创建 DisruptorX 节点
    val node = DisruptorX.createNode(config)
    
    try {
        // 初始化节点
        println("初始化节点...")
        node.initialize()
        
        // 订阅事件
        println("订阅事件...")
        node.eventBus.subscribe("test.topic") { event ->
            println("收到事件: $event")
        }
        
        // 发布事件
        println("发布事件...")
        repeat(5) { i ->
            val event = "测试事件 #$i"
            node.eventBus.publish(event, "test.topic")
            delay(100)
        }
        
        // 等待事件处理
        delay(1000)
        
        println("基础功能验证完成")
        
    } catch (e: Exception) {
        println("错误: ${e.message}")
        e.printStackTrace()
    } finally {
        // 关闭节点
        println("关闭节点...")
        node.shutdown()
    }
}