# 基于Disruptor和Akka的高频交易数据中心开发计划

## 1. 项目概述

### 1.1 背景

高频交易系统需要处理大量并发事务，具有极低的延迟要求和高可靠性。本项目旨在结合LMAX Disruptor（高性能内存队列）和Akka（响应式分布式系统框架）的优势，构建一个高性能、可扩展和可靠的高频交易数据中心。

### 1.2 技术选型

- **编程语言**: Kotlin (JVM)
- **构建工具**: Gradle (Kotlin DSL)
- **核心技术**:
  - LMAX Disruptor: 用于高性能、低延迟的内存队列
  - Akka: 用于构建响应式、分布式系统
  - Kotlin Coroutines: 用于异步编程
  - Arrow: 函数式编程库
  - Chronicle Wire: 高性能序列化
  - Adaptive Radix Trees: 高效数据结构
  - PostgreSQL/TimescaleDB: 时序数据存储
  - Prometheus/Grafana: 监控与指标收集 ✅

## 2. 系统架构

### 2.1 总体架构

系统采用分层架构，主要包含以下几部分：

1. **网关层**：处理外部连接和协议转换
2. **订单匹配引擎**：基于Disruptor实现的高性能匹配引擎
3. **风控与账户管理**：处理风险控制和账户操作
4. **持久化层**：负责日志和快照持久化
5. **市场数据分发**：处理市场数据的分发
6. **API服务**：提供交易、管理和报表API

### 2.2 详细设计

#### 2.2.1 Disruptor架构

```
                   +----------------+
                   |                |
                   |   Producer     |
                   |                |
                   +-------+--------+
                           |
                           v
+--------------------------------------------------+
|                     Ring Buffer                  |
+--------------------------------------------------+
       ^                 ^                  ^
       |                 |                  |
       v                 v                  v
+-------------+  +---------------+  +---------------+
|             |  |               |  |               |
| Consumer 1  |  |  Consumer 2   |  |  Consumer 3   |
| (风控验证)   |  | (订单匹配)     |  | (日志记录)     |
|             |  |               |  |               |
+-------------+  +---------------+  +---------------+
```

订单处理流程将使用Disruptor作为核心，实现高性能的内存队列和事件处理。

#### 2.2.2 Akka架构

```
                  +------------------+
                  |                  |
                  |  Gateway Actor   |
                  |                  |
                  +--------+---------+
                           |
                           v
          +----------------+----------------+
          |                                 |
          |         Dispatcher Actor        |
          |                                 |
          +----+-------------+-------------+
               |             |             |
               v             v             v
    +-----------+   +----------+   +-----------+
    |           |   |          |   |           |
    | 订单处理   |   | 市场数据  |   | 用户管理   |
    | Actors    |   | Actors   |   | Actors    |
    |           |   |          |   |           |
    +-----------+   +----------+   +-----------+
```

Akka Actor模型将用于构建系统的各个组件，实现消息驱动的异步处理和容错机制。

### 2.3 关键组件

#### 2.3.1 订单匹配引擎 ✅

- 基于Disruptor实现高性能的订单匹配
- 支持多种订单类型：市价单、限价单、IOC单、FOK单、POST_ONLY单
- 采用价格优先、时间优先的匹配算法

#### 2.3.2 风控系统 ✅

- 实时风险控制，包括资金检查、持仓限制、交易频率控制等
- 基于规则引擎的风控策略管理
- 支持动态调整风控参数

#### 2.3.3 日志与快照系统 ✅

- 基于事件溯源模式实现交易日志
- 定期生成系统状态快照，支持系统恢复
- 使用JSON序列化实现事件和快照的持久化

#### 2.3.4 市场数据处理 ✅

- 接收和处理外部市场数据
- 生成自身订单簿的市场数据
- 支持多种频率的市场数据推送

## 3. 技术实现详解

### 3.1 Disruptor实现

```kotlin
// 订单事件定义
data class OrderEvent(
    val orderId: Long,
    val userId: Long,
    val instrumentId: String,
    val price: Long,
    val quantity: Long,
    val side: OrderSide,
    val type: OrderType,
    val timestamp: Long
)

// Disruptor配置
val disruptor = Disruptor<OrderEvent>(
    OrderEvent::class.java,
    bufferSize,
    DaemonThreadFactory.INSTANCE,
    ProducerType.MULTI,
    WaitStrategy()
)

// 消费者配置
disruptor.handleEventsWith(riskCheckHandler)
    .then(orderMatchHandler)
    .then(journalHandler)
```

### 3.2 Akka实现

```kotlin
// 订单处理Actor
class OrderProcessorActor : AbstractActor() {
    private val log = Logging.getLogger(context.system, this)
    private val instrumentProcessors = mutableMapOf<String, ActorRef>()

    override fun createReceive(): Receive = receiveBuilder()
        .match(OrderCommand::class.java) { command ->
            val processor = getOrCreateProcessor(command.instrumentId)
            processor.tell(command, self)
        }
        .match(ProcessorIdle::class.java) { 
            removeIdleProcessor(sender)
            sender.tell(PoisonPill.getInstance(), self)
        }
        .build()

    private fun getOrCreateProcessor(instrumentId: String): ActorRef {
        return instrumentProcessors.getOrPut(instrumentId) {
            context.actorOf(
                Props.create(InstrumentProcessorActor::class.java),
                "processor-$instrumentId"
            )
        }
    }

    private fun removeIdleProcessor(processor: ActorRef) {
        instrumentProcessors.entries.find { it.value == processor }?.let {
            instrumentProcessors.remove(it.key)
        }
    }
}
```

### 3.3 订单簿实现 ✅

```kotlin
// 使用Adaptive Radix Trees实现高效订单簿
class OrderBook(val instrumentId: String) {
    private val buyOrders = TreeMap<Long, MutableList<Order>>(Comparator.reverseOrder()) // 价格倒序，买单从高到低
    private val sellOrders = TreeMap<Long, MutableList<Order>>() // 价格正序，卖单从低到高
    
    fun addOrder(order: Order): List<Trade> {
        // 根据订单类型处理
        if (order.type == OrderType.MARKET) {
            return matchMarketOrder(order)
        } else if (order.type == OrderType.POST_ONLY) {
            return handlePostOnlyOrder(order)
        } else if (order.type == OrderType.FOK || order.timeInForce == TimeInForce.FOK) {
            return handleFokOrder(order)
        } else if (order.type == OrderType.IOC || order.timeInForce == TimeInForce.IOC) {
            return handleIocOrder(order)
        }
        
        // 限价单匹配
        return when(order.side) {
            OrderSide.BUY -> matchBuyOrder(order)
            OrderSide.SELL -> matchSellOrder(order)
        }
    }
    
    fun cancelOrder(orderId: Long): Boolean {
        // 取消订单实现
    }
    
    // 支持多种订单类型的具体实现
    private fun matchBuyOrder(order: Order): List<Trade> {
        // 买单匹配逻辑
    }
    
    private fun matchSellOrder(order: Order): List<Trade> {
        // 卖单匹配逻辑
    }
    
    private fun matchMarketOrder(order: Order): List<Trade> {
        // 市价单匹配逻辑
    }
    
    private fun handlePostOnlyOrder(order: Order): List<Trade> {
        // POST_ONLY订单处理逻辑
    }
    
    private fun handleFokOrder(order: Order): List<Trade> {
        // FOK订单处理逻辑
    }
    
    private fun handleIocOrder(order: Order): List<Trade> {
        // IOC订单处理逻辑
    }
}
```

### 3.4 事件溯源与持久化

```kotlin
// 事件定义
sealed class Event {
    data class OrderPlaced(val order: Order) : Event()
    data class OrderExecuted(val trade: Trade) : Event()
    data class OrderCancelled(val orderId: Long) : Event()
}

// 状态恢复
fun restoreState(events: List<Event>): OrderBook {
    val orderBook = OrderBook("INSTRUMENT")
    events.forEach { event ->
        when(event) {
            is OrderPlaced -> orderBook.addOrder(event.order)
            is OrderExecuted -> orderBook.executeTrade(event.trade)
            is OrderCancelled -> orderBook.cancelOrder(event.orderId)
        }
    }
    return orderBook
}
```

## 4. 开发计划

### 4.1 阶段一：基础架构与核心组件 (8周)

1. **第1-2周**：项目设置与环境搭建
   - 建立Gradle构建系统
   - 配置开发环境
   - 设置CI/CD流程

2. **第3-5周**：核心组件开发
   - 实现基于Disruptor的订单处理流水线
   - 开发订单匹配引擎核心
   - 构建基于Akka的Actor系统

3. **第6-8周**：基础功能实现
   - 完成订单簿实现
   - 实现基本的风控检查
   - 开发日志与持久化机制

### 4.2 阶段二：高级功能与集成 (6周)

4. **第9-10周**：高级功能开发
   - 增强风控系统 ✅
   - 市场数据处理 ✅
   - 完善订单类型支持 ✅

5. **第11-14周**：系统集成与测试
   - 组件集成
   - 性能测试与优化
   - 压力测试与稳定性测试

### 4.3 阶段三：优化与部署 (4周)

6. **第15-16周**：性能优化
   - 内存使用优化
   - GC优化
   - 延迟优化

7. **第17-18周**：生产环境部署
   - 监控系统配置 ✅
   - 生产环境配置
   - 部署与上线

## 5. API设计 ✅

### 5.1 交易API ✅

#### 5.1.1 订单操作

```