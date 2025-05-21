# 高频交易数据中心

基于Disruptor和Akka实现的高性能、低延迟高频交易数据中心，使用Kotlin语言开发。

## 项目概述

本项目是一个高性能交易系统，具有以下特点：

- 基于LMAX Disruptor实现的高性能、低延迟订单处理流水线
- 使用Akka Actor模型实现分布式、容错系统
- 完全基于Kotlin语言实现，提供简洁、安全的代码
- 支持多种订单类型和高级交易功能
- 高度优化的订单匹配引擎
- 实时风险控制
- 基于事件溯源的持久化设计

## 技术栈

- Kotlin 1.8+
- Gradle (Kotlin DSL)
- LMAX Disruptor
- Akka Actor
- Chronicle Wire
- PostgreSQL/TimescaleDB

## 快速开始

### 前置条件

- JDK 17+
- Gradle 7.4+
- PostgreSQL 12+

### 构建

```bash
./gradlew build
```

### 运行

```bash
./gradlew run
```

### 测试

```bash
./gradlew test
```

## 项目结构

- `/src/main/kotlin/com/hftdc/` - 主要源代码
  - `/api/` - API接口
  - `/config/` - 配置相关
  - `/core/` - 核心系统组件
  - `/engine/` - 交易引擎
  - `/model/` - 领域模型
  - `/persistence/` - 持久化层
  - `/service/` - 业务服务
  - `/util/` - 工具类

## 架构

系统采用分层架构，主要包含以下几部分：

1. **网关层**：处理外部连接和协议转换
2. **订单匹配引擎**：基于Disruptor实现的高性能匹配引擎
3. **风控与账户管理**：处理风险控制和账户操作
4. **持久化层**：负责日志和快照持久化
5. **市场数据分发**：处理市场数据的分发
6. **API服务**：提供交易、管理和报表API

详细架构请参考 [plan1.md](plan1.md) 文件。

## 配置

系统配置位于 `src/main/resources/application.conf`，主要包含以下部分：

- Disruptor配置
- Akka配置
- 数据库配置
- 引擎配置

## 性能

- 订单处理延迟：平均 <100µs，99.9%分位 <1ms
- 系统吞吐量：支持每秒处理至少100万个订单
- 容量：支持10万个订单簿，同时保持400万个未成交订单

## 许可证

[Apache License 2.0](LICENSE) 