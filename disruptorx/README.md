# DisruptorX: 分布式Disruptor工作流框架

DisruptorX是基于LMAX Disruptor的分布式工作流框架，旨在提供高性能、低延迟的事件处理能力，同时扩展LMAX Disruptor的功能以支持分布式环境下的事件处理和工作流编排。框架利用Kotlin DSL提供直观的API，使开发人员能够轻松定义复杂的数据处理流程。

## 核心特性

- **分布式事件总线**: 跨进程、跨机器的事件发布与订阅
- **动态工作流**: 运行时可配置的事件处理流程
- **高性能**: 保持Disruptor的低延迟特性（微秒级延迟）
- **容错与恢复**: 分布式环境下的故障恢复机制
- **Kotlin DSL**: 提供简洁、类型安全的配置方式
- **与现有系统集成**: 与高频交易数据中心现有架构的无缝对接

## 设计架构

DisruptorX采用分层架构，主要包含以下几个层次：

```
+-----------------------------------+
|           Workflow DSL            |
+-----------------------------------+
|        Distributed EventBus       |
+-----------------------------------+
|         Node Management           |
+-----------------------------------+
|           Core Engine             |
+-----------------------------------+
|       Transport & Protocol        |
+-----------------------------------+
```

- **Core Engine**: 基于LMAX Disruptor的核心引擎，负责单节点内的事件处理
- **Node Management**: 节点管理层，负责节点发现、健康检查和负载均衡
- **Distributed EventBus**: 分布式事件总线，提供跨节点的事件发布与订阅
- **Workflow DSL**: 基于Kotlin DSL的工作流定义语言
- **Transport & Protocol**: 节点间通信协议和传输层，基于Netty

## 快速开始

### 添加依赖

```kotlin
// 在build.gradle.kts中添加依赖
implementation("com.hftdc:disruptorx:1.0.0")
```

### 创建工作流

使用Kotlin DSL定义工作流：

```kotlin
val workflow = workflow("orderProcessing", "Order Processing Workflow") {
    source {
        fromTopic("orders")
        partitionBy { order -> (order as Order).orderId.hashCode() }
    }
    
    stages {
        stage("validation") {
            handler { event ->
                val order = event as Order
                // 验证逻辑
            }
        }
        
        stage("enrichment") {
            handler { event ->
                val order = event as Order
                // 充实订单信息
            }
        }
        
        stage("processing") {
            parallelism = 4 // 使用4个并行处理器
            handler { event ->
                val order = event as Order
                // 处理订单
            }
        }
    }
    
    sink {
        toTopic("processed-orders")
    }
}
```

### 创建和启动节点

```kotlin
// 创建节点
val node = DisruptorX.createNode(
    DisruptorXConfig(
        host = "localhost",
        port = 9090
    )
)

// 初始化节点
node.initialize()

// 注册工作流
node.workflowManager.register(workflow)

// 启动工作流
node.workflowManager.start(workflow.id)

// 发布事件
runBlocking {
    node.eventBus.publish(Order("ORD-1", "CUST-1", listOf(/* items */)), "orders")
}
```

## 高级用法

### 集群配置

```kotlin
// 创建节点
val node = DisruptorX.createNode(
    DisruptorXConfig(
        nodeId = "node-1",
        host = "192.168.1.100",
        port = 9090,
        nodeRole = NodeRole.MIXED,
        heartbeatIntervalMillis = 500,
        nodeTimeoutIntervalMillis = 2000
    )
)

// 初始化并加入集群
node.initialize()
node.joinCluster("192.168.1.101:9090,192.168.1.102:9090")
```

### 等待策略优化

DisruptorX提供多种等待策略，可以根据不同场景选择：

```kotlin
// 创建自适应等待策略
val waitStrategy = WaitStrategyFactory.createAdaptiveWaitStrategy(
    yieldThreshold = 100,
    sleepThreshold = 1000,
    parkThreshold = 10000
)

// 使用忙等待策略获取最低延迟（但CPU使用率高）
val busySpinStrategy = WaitStrategyFactory.createBusySpinWaitStrategy()
```

## 性能优化

DisruptorX采用多种性能优化技术：

1. **对象池**: 使用对象池减少GC压力
2. **批处理**: 批量发送和处理事件
3. **本地优先**: 优先本地处理事件，避免不必要的网络传输
4. **分布式序列**: 高效的分布式序列号生成和同步机制
5. **自适应等待策略**: 根据系统负载自动调整等待策略

## 完整示例

请参考`com.hftdc.disruptorx.example.OrderProcessingExample`类查看完整的使用示例。

## 许可证

DisruptorX基于Apache License 2.0开源。 