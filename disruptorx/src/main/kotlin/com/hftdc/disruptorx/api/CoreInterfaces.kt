package com.hftdc.disruptorx.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * 基础事件接口
 */
interface Event {
    val id: String
    val timestamp: Long
    val type: String
}

/**
 * 分布式事件总线接口
 * 提供跨节点的事件发布与订阅功能
 */
interface DistributedEventBus {
    /**
     * 发布事件到指定主题
     * @param event 要发布的事件
     * @param topic 目标主题
     */
    suspend fun publish(event: Any, topic: String)
    
    /**
     * 订阅指定主题的事件
     * @param topic 要订阅的主题
     * @param handler 事件处理函数
     */
    fun subscribe(topic: String, handler: suspend (Any) -> Unit)
    
    /**
     * 取消订阅指定主题
     * @param topic 主题名称
     * @param handler 要取消的处理函数
     */
    fun unsubscribe(topic: String, handler: suspend (Any) -> Unit)
}

/**
 * 工作流管理器接口
 * 负责工作流的注册、启动、停止和监控
 */
interface WorkflowManager {
    /**
     * 注册工作流
     * @param workflow 工作流定义
     */
    fun register(workflow: Workflow)
    
    /**
     * 启动工作流
     * @param workflowId 工作流ID
     */
    fun start(workflowId: String)
    
    /**
     * 停止工作流
     * @param workflowId 工作流ID
     */
    fun stop(workflowId: String)
    
    /**
     * 更新工作流
     * @param workflow 更新后的工作流定义
     */
    fun update(workflow: Workflow)
    
    /**
     * 获取工作流状态
     * @param workflowId 工作流ID
     * @return 工作流状态
     */
    fun status(workflowId: String): WorkflowStatus
}

/**
 * 节点管理器接口
 * 负责节点的加入、离开和状态监控
 */
interface NodeManager {
    /**
     * 加入集群
     * @param cluster 集群连接字符串
     */
    fun join(cluster: String)
    
    /**
     * 离开集群
     */
    fun leave()
    
    /**
     * 获取集群成员列表
     * @return 集群成员信息列表
     */
    fun getClusterMembers(): List<NodeInfo>
    
    /**
     * 获取当前集群领导节点
     * @return 领导节点信息
     */
    fun getLeader(): NodeInfo
}

/**
 * 事件处理接口
 * 基于LMAX Disruptor的事件处理器
 */
interface EventHandler<T> {
    /**
     * 处理事件
     * @param event 事件对象
     * @param sequence 事件序列号
     * @param endOfBatch 是否为批次的最后一个
     */
    fun onEvent(event: T, sequence: Long, endOfBatch: Boolean)
}

/**
 * 事件发布接口
 * 提供事件发布功能
 */
interface EventPublisher {
    /**
     * 发布单个事件
     * @param event 要发布的事件
     */
    fun publishEvent(event: Any)
    
    /**
     * 批量发布事件
     * @param events 要发布的事件集合
     */
    fun publishEvents(events: Collection<Any>)
}

/**
 * 工作流阶段接口
 * 表示工作流中的一个处理阶段
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
     * 获取事件处理器
     * @return 事件处理器
     */
    fun getHandler(): EventHandler<Any>
}

/**
 * 分布式序列屏障
 * 基于LMAX Disruptor的SequenceBarrier扩展到分布式环境
 */
interface DistributedSequenceBarrier {
    /**
     * 等待序列号达到指定值
     * @param sequence 目标序列号
     * @return 当前可用的序列号
     */
    fun waitFor(sequence: Long): Long
    
    /**
     * 等待序列号达到指定值，带超时
     * @param sequence 目标序列号
     * @param timeout 超时时间
     * @return 当前可用的序列号
     */
    fun waitFor(sequence: Long, timeout: Duration): Long
    
    /**
     * 获取当前游标位置
     * @return 当前序列号
     */
    fun getCursor(): Long
    
    /**
     * 检查是否有警报
     * 如果有警报会抛出异常
     */
    fun checkAlert()
}

/**
 * 分布式序列
 * 基于LMAX Disruptor的Sequence扩展到分布式环境
 */
interface DistributedSequence {
    /**
     * 获取当前序列值
     * @return 当前序列值
     */
    fun get(): Long
    
    /**
     * 设置序列值
     * @param value 新的序列值
     */
    fun set(value: Long)
    
    /**
     * 增加序列值并返回
     * @return 增加后的序列值
     */
    fun incrementAndGet(): Long
    
    /**
     * 增加指定值并返回
     * @param increment 要增加的值
     * @return 增加后的序列值
     */
    fun addAndGet(increment: Long): Long
}

/**
 * 工作流接口
 * 表示一个完整的工作流定义
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
     * 工作流来源配置
     */
    val source: WorkflowSource
    
    /**
     * 工作流阶段列表
     */
    val stages: List<WorkflowStage>
    
    /**
     * 工作流输出配置
     */
    val sink: WorkflowSink
}

/**
 * 工作流来源接口
 * 表示工作流的数据来源
 */
interface WorkflowSource {
    /**
     * 来源主题
     */
    val topic: String
    
    /**
     * 分区策略
     */
    val partitionStrategy: PartitionStrategy
}

/**
 * 工作流输出接口
 * 表示工作流的数据输出目标
 */
interface WorkflowSink {
    /**
     * 输出主题
     */
    val topic: String
}

/**
 * 工作流状态枚举
 */
enum class WorkflowStatus {
    /**
     * 已创建但未启动
     */
    CREATED,
    
    /**
     * 正在启动
     */
    STARTING,
    
    /**
     * 运行中
     */
    RUNNING,
    
    /**
     * 正在停止
     */
    STOPPING,
    
    /**
     * 已停止
     */
    STOPPED,
    
    /**
     * 发生错误
     */
    ERROR
}

/**
 * 节点信息数据类
 */
@Serializable
data class NodeInfo(
    /**
     * 节点ID
     */
    val nodeId: String,
    
    /**
     * 主机地址
     */
    val host: String,
    
    /**
     * 端口
     */
    val port: Int,
    
    /**
     * 是否为领导节点
     */
    val isLeader: Boolean,
    
    /**
     * 节点角色
     */
    val role: NodeRole,
    
    /**
     * 节点状态
     */
    val status: NodeStatus
)

/**
 * 节点角色枚举
 */
enum class NodeRole {
    /**
     * 协调器节点
     */
    COORDINATOR,
    
    /**
     * 工作节点
     */
    WORKER,
    
    /**
     * 协调器+工作节点
     */
    MIXED
}

/**
 * 节点状态枚举
 */
enum class NodeStatus {
    /**
     * 正在加入
     */
    JOINING,
    
    /**
     * 活跃
     */
    ACTIVE,
    
    /**
     * 正在离开
     */
    LEAVING,
    
    /**
     * 不可达
     */
    UNREACHABLE
}

/**
 * 分区策略接口
 */
interface PartitionStrategy {
    /**
     * 根据键计算分区
     * @param key 分区键
     * @param partitionCount 分区总数
     * @return 分区ID
     */
    fun getPartition(key: Any, partitionCount: Int): Int
    
    companion object {
        /**
         * 一致性哈希分区策略
         */
        val CONSISTENT_HASH = object : PartitionStrategy {
            override fun getPartition(key: Any, partitionCount: Int): Int {
                return Math.abs(key.hashCode() % partitionCount)
            }
        }
        
        /**
         * 轮询分区策略
         */
        val ROUND_ROBIN = object : PartitionStrategy {
            private val counter = java.util.concurrent.atomic.AtomicLong(0)
            
            override fun getPartition(key: Any, partitionCount: Int): Int {
                return (counter.getAndIncrement() % partitionCount).toInt()
            }
        }
    }
}