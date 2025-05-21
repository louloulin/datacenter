package com.hftdc.disruptorx.core

import com.hftdc.disruptorx.api.DistributedEventBus
import com.hftdc.disruptorx.api.EventHandler
import com.hftdc.disruptorx.api.Workflow
import com.hftdc.disruptorx.api.WorkflowManager
import com.hftdc.disruptorx.api.WorkflowStatus
import com.hftdc.disruptorx.api.WorkflowStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * 工作流管理器实现
 * 负责工作流的注册、启动、停止和监控
 *
 * @property eventBus 分布式事件总线
 * @property config 工作流管理器配置
 */
class WorkflowManagerImpl(
    private val eventBus: DistributedEventBus,
    private val config: WorkflowManagerConfig
) : WorkflowManager, CoroutineScope {
    
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Default + job
    
    // 工作流注册表，存储所有已注册的工作流
    private val workflowRegistry = ConcurrentHashMap<String, Workflow>()
    
    // 工作流状态表，存储所有工作流的状态
    private val workflowStatus = ConcurrentHashMap<String, WorkflowStatus>()
    
    // 工作流处理器表，存储每个工作流的事件处理器
    private val workflowHandlers = ConcurrentHashMap<String, MutableList<suspend (Any) -> Unit>>()
    
    // 工作流执行器，用于并行处理事件
    private val executorServices = ConcurrentHashMap<String, ExecutorService>()
    
    // 操作锁，确保工作流操作线程安全
    private val operationLocks = ConcurrentHashMap<String, Mutex>()
    
    /**
     * 注册工作流
     * @param workflow 工作流定义
     */
    override fun register(workflow: Workflow) {
        // 检查工作流ID是否已存在
        if (workflowRegistry.containsKey(workflow.id)) {
            throw IllegalArgumentException("Workflow with ID ${workflow.id} already exists")
        }
        
        // 注册工作流
        workflowRegistry[workflow.id] = workflow
        workflowStatus[workflow.id] = WorkflowStatus.CREATED
        operationLocks[workflow.id] = Mutex()
        
        // 为工作流创建处理器列表
        workflowHandlers[workflow.id] = mutableListOf()
    }
    
    /**
     * 启动工作流
     * @param workflowId 工作流ID
     */
    override fun start(workflowId: String) {
        val workflow = getWorkflow(workflowId)
        
        launch {
            val lock = operationLocks[workflowId] ?: return@launch
            lock.withLock {
                // 检查当前状态
                if (workflowStatus[workflowId] == WorkflowStatus.RUNNING) {
                    return@withLock
                }
                
                try {
                    // 更新状态为启动中
                    workflowStatus[workflowId] = WorkflowStatus.STARTING
                    
                    // 创建执行器服务
                    val executorService = createExecutorService(workflow)
                    executorServices[workflowId] = executorService
                    
                    // 注册工作流处理器
                    registerWorkflowHandlers(workflow)
                    
                    // 更新状态为运行中
                    workflowStatus[workflowId] = WorkflowStatus.RUNNING
                } catch (e: Exception) {
                    // 启动失败，更新状态为错误
                    workflowStatus[workflowId] = WorkflowStatus.ERROR
                    throw e
                }
            }
        }
    }
    
    /**
     * 停止工作流
     * @param workflowId 工作流ID
     */
    override fun stop(workflowId: String) {
        val workflow = getWorkflow(workflowId)
        
        launch {
            val lock = operationLocks[workflowId] ?: return@launch
            lock.withLock {
                // 检查当前状态
                val currentStatus = workflowStatus[workflowId]
                if (currentStatus != WorkflowStatus.RUNNING && currentStatus != WorkflowStatus.STARTING) {
                    return@withLock
                }
                
                try {
                    // 更新状态为停止中
                    workflowStatus[workflowId] = WorkflowStatus.STOPPING
                    
                    // 取消注册处理器
                    unregisterWorkflowHandlers(workflow)
                    
                    // 关闭执行器服务
                    executorServices[workflowId]?.shutdown()
                    executorServices.remove(workflowId)
                    
                    // 更新状态为已停止
                    workflowStatus[workflowId] = WorkflowStatus.STOPPED
                } catch (e: Exception) {
                    // 停止失败，更新状态为错误
                    workflowStatus[workflowId] = WorkflowStatus.ERROR
                    throw e
                }
            }
        }
    }
    
    /**
     * 更新工作流
     * @param workflow 更新后的工作流定义
     */
    override fun update(workflow: Workflow) {
        val workflowId = workflow.id
        
        // 检查工作流是否存在
        if (!workflowRegistry.containsKey(workflowId)) {
            throw IllegalArgumentException("Workflow with ID $workflowId does not exist")
        }
        
        launch {
            val lock = operationLocks[workflowId] ?: return@launch
            lock.withLock {
                val currentStatus = workflowStatus[workflowId]
                val isRunning = currentStatus == WorkflowStatus.RUNNING
                
                try {
                    // 如果工作流正在运行，先停止
                    if (isRunning) {
                        workflowStatus[workflowId] = WorkflowStatus.STOPPING
                        unregisterWorkflowHandlers(workflow)
                        executorServices[workflowId]?.shutdown()
                        executorServices.remove(workflowId)
                    }
                    
                    // 更新工作流定义
                    workflowRegistry[workflowId] = workflow
                    
                    // 如果之前在运行，重新启动
                    if (isRunning) {
                        workflowStatus[workflowId] = WorkflowStatus.STARTING
                        
                        // 创建新的执行器服务
                        val executorService = createExecutorService(workflow)
                        executorServices[workflowId] = executorService
                        
                        // 注册新的工作流处理器
                        registerWorkflowHandlers(workflow)
                        
                        // 更新状态为运行中
                        workflowStatus[workflowId] = WorkflowStatus.RUNNING
                    } else {
                        // 如果之前不在运行，仅更新状态
                        workflowStatus[workflowId] = WorkflowStatus.CREATED
                    }
                } catch (e: Exception) {
                    // 更新失败，更新状态为错误
                    workflowStatus[workflowId] = WorkflowStatus.ERROR
                    throw e
                }
            }
        }
    }
    
    /**
     * 获取工作流状态
     * @param workflowId 工作流ID
     * @return 工作流状态
     */
    override fun status(workflowId: String): WorkflowStatus {
        return workflowStatus[workflowId] ?: throw IllegalArgumentException("Workflow with ID $workflowId does not exist")
    }
    
    /**
     * 获取工作流定义
     * @param workflowId 工作流ID
     * @return 工作流定义
     */
    private fun getWorkflow(workflowId: String): Workflow {
        return workflowRegistry[workflowId] ?: throw IllegalArgumentException("Workflow with ID $workflowId does not exist")
    }
    
    /**
     * 创建工作流执行器服务
     * @param workflow 工作流定义
     * @return 执行器服务
     */
    private fun createExecutorService(workflow: Workflow): ExecutorService {
        // 计算总线程数
        val threadCount = workflow.stages.sumOf { it.parallelism }
        return Executors.newFixedThreadPool(threadCount)
    }
    
    /**
     * 注册工作流处理器
     * @param workflow 工作流定义
     */
    private suspend fun registerWorkflowHandlers(workflow: Workflow) {
        val workflowId = workflow.id
        val handlersList = workflowHandlers.getOrPut(workflowId) { mutableListOf() }
        
        // 清空现有处理器
        handlersList.clear()
        
        // 创建工作流处理管道
        val stages = workflow.stages
        val stageMap = stages.associateBy { it.id }
        
        // 为每个阶段创建处理器
        for (stage in stages) {
            val stageId = stage.id
            val handler = stage.getHandler()
            
            // 创建阶段处理函数
            val stageHandler: suspend (Any) -> Unit = { event ->
                handleStageEvent(event, handler, workflow, stageId)
            }
            
            handlersList.add(stageHandler)
        }
        
        // 订阅来源主题
        eventBus.subscribe(workflow.source.topic) { event ->
            handlersList.forEach { handler ->
                launch {
                    handler(event)
                }
            }
        }
    }
    
    /**
     * 处理阶段事件
     * @param event 事件对象
     * @param handler 事件处理器
     * @param workflow 工作流定义
     * @param stageId 阶段ID
     */
    private fun handleStageEvent(
        event: Any,
        handler: EventHandler<Any>,
        workflow: Workflow,
        stageId: String
    ) {
        // 调用处理器处理事件
        try {
            handler.onEvent(event, 0, false)
            
            // 如果是最后一个阶段，发布到输出主题
            if (stageId == workflow.stages.last().id) {
                launch {
                    eventBus.publish(event, workflow.sink.topic)
                }
            }
        } catch (e: Exception) {
            // 处理异常
            // TODO: 添加错误处理策略和日志记录
        }
    }
    
    /**
     * 取消注册工作流处理器
     * @param workflow 工作流定义
     */
    private suspend fun unregisterWorkflowHandlers(workflow: Workflow) {
        // 取消订阅来源主题
        workflowHandlers[workflow.id]?.let { handlers ->
            for (handler in handlers) {
                eventBus.unsubscribe(workflow.source.topic, handler)
            }
            handlers.clear()
        }
    }
}

/**
 * 工作流管理器配置
 */
data class WorkflowManagerConfig(
    val maxConcurrentWorkflows: Int = 100,
    val defaultExecutorThreads: Int = Runtime.getRuntime().availableProcessors()
) 