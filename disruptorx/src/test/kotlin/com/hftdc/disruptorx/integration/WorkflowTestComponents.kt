package com.hftdc.disruptorx.integration

import com.hftdc.disruptorx.monitoring.MetricsCollector
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Workflow definition for tests
 */
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val steps: List<WorkflowStep>,
    val timeout: Duration = 30.seconds
)

/**
 * Workflow step definition
 */
data class WorkflowStep(
    val name: String,
    val type: StepType,
    val handler: String,
    val input: Map<String, Any> = emptyMap(),
    val retryPolicy: RetryPolicy? = null
)

/**
 * Step types
 */
enum class StepType {
    TASK,
    WAIT,
    CONDITION,
    PARALLEL
}

/**
 * Retry policy
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val delay: Duration = 100.milliseconds,
    val backoffMultiplier: Double = 2.0
)

/**
 * Workflow status
 */
enum class WorkflowStatus {
    CREATED,
    RUNNING,
    PAUSED,
    STOPPED,
    COMPLETED,
    FAILED,
    ERROR
}

/**
 * Workflow instance
 */
data class WorkflowInstance(
    val instanceId: String,
    val workflowId: String,
    val status: WorkflowStatus,
    val input: Map<String, Any>,
    val output: Map<String, Any> = emptyMap(),
    val error: String? = null,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val currentStep: String? = null
)

/**
 * Enhanced workflow manager for tests
 */
class EnhancedWorkflowManager(private val metricsCollector: MetricsCollector) {
    private val workflowDefinitions = ConcurrentHashMap<String, WorkflowDefinition>()
    private val workflowInstances = ConcurrentHashMap<String, WorkflowInstance>()
    private val instanceCounter = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    @Volatile
    private var isRunning = false
    
    fun initialize() {
        isRunning = true
        startMonitoring()
    }
    
    fun shutdown() {
        isRunning = false
        scope.cancel()
        workflowDefinitions.clear()
        workflowInstances.clear()
    }
    
    fun registerWorkflow(definition: WorkflowDefinition) {
        workflowDefinitions[definition.id] = definition
        metricsCollector.incrementCounter("workflow.registered")
    }
    
    fun startWorkflow(workflowId: String, input: Map<String, Any> = emptyMap()): String {
        val definition = workflowDefinitions[workflowId] 
            ?: throw IllegalArgumentException("Workflow not found: $workflowId")
        
        val instanceId = "wf_${instanceCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        val instance = WorkflowInstance(
            instanceId = instanceId,
            workflowId = workflowId,
            status = WorkflowStatus.RUNNING,
            input = input,
            currentStep = definition.steps.firstOrNull()?.name
        )
        
        workflowInstances[instanceId] = instance
        metricsCollector.incrementCounter("workflow.started")
        
        // Start workflow execution in background
        scope.launch {
            executeWorkflow(instanceId, definition)
        }
        
        return instanceId
    }
    
    fun pauseWorkflow(instanceId: String) {
        val instance = workflowInstances[instanceId] ?: return
        if (instance.status == WorkflowStatus.RUNNING) {
            workflowInstances[instanceId] = instance.copy(status = WorkflowStatus.PAUSED)
            metricsCollector.incrementCounter("workflow.paused")
        }
    }
    
    fun resumeWorkflow(instanceId: String) {
        val instance = workflowInstances[instanceId] ?: return
        if (instance.status == WorkflowStatus.PAUSED) {
            workflowInstances[instanceId] = instance.copy(status = WorkflowStatus.RUNNING)
            metricsCollector.incrementCounter("workflow.resumed")
        }
    }
    
    fun stopWorkflow(instanceId: String, reason: String? = null) {
        val instance = workflowInstances[instanceId] ?: return
        workflowInstances[instanceId] = instance.copy(
            status = WorkflowStatus.STOPPED,
            error = reason,
            endTime = System.currentTimeMillis()
        )
        metricsCollector.incrementCounter("workflow.stopped")
    }
    
    fun getWorkflowStatus(instanceId: String): WorkflowInstance? {
        return workflowInstances[instanceId]
    }
    
    fun getRunningWorkflows(): List<WorkflowInstance> {
        return workflowInstances.values.filter { it.status == WorkflowStatus.RUNNING }
    }
    
    private suspend fun executeWorkflow(instanceId: String, definition: WorkflowDefinition) {
        try {
            for (step in definition.steps) {
                val instance = workflowInstances[instanceId] ?: return
                
                // Check if workflow is paused or stopped
                while (instance.status == WorkflowStatus.PAUSED) {
                    delay(100)
                }
                
                if (instance.status == WorkflowStatus.STOPPED) {
                    return
                }
                
                // Update current step
                workflowInstances[instanceId] = instance.copy(currentStep = step.name)
                
                // Execute step
                executeStep(step)
                
                // Small delay between steps
                delay(50)
            }
            
            // Mark as completed
            val instance = workflowInstances[instanceId] ?: return
            workflowInstances[instanceId] = instance.copy(
                status = WorkflowStatus.COMPLETED,
                endTime = System.currentTimeMillis()
            )
            metricsCollector.incrementCounter("workflow.completed")
            
        } catch (e: Exception) {
            val instance = workflowInstances[instanceId] ?: return
            workflowInstances[instanceId] = instance.copy(
                status = WorkflowStatus.FAILED,
                error = e.message,
                endTime = System.currentTimeMillis()
            )
            metricsCollector.incrementCounter("workflow.failed")
        }
    }
    
    private suspend fun executeStep(step: WorkflowStep) {
        when (step.type) {
            StepType.TASK -> {
                // Simulate task execution
                delay(10)
            }
            StepType.WAIT -> {
                val duration = step.input["duration"] as? Long ?: 100L
                delay(duration)
            }
            StepType.CONDITION -> {
                // Simulate condition evaluation
                delay(5)
            }
            StepType.PARALLEL -> {
                // Simulate parallel execution
                delay(20)
            }
        }
    }
    
    private fun startMonitoring() {
        scope.launch {
            while (isRunning) {
                delay(1000) // Update every second
                
                val runningCount = workflowInstances.values.count { it.status == WorkflowStatus.RUNNING }
                val pausedCount = workflowInstances.values.count { it.status == WorkflowStatus.PAUSED }
                val completedCount = workflowInstances.values.count { it.status == WorkflowStatus.COMPLETED }
                val failedCount = workflowInstances.values.count { it.status == WorkflowStatus.FAILED }
                
                metricsCollector.setGauge("workflow.instances.running", runningCount.toDouble())
                metricsCollector.setGauge("workflow.instances.paused", pausedCount.toDouble())
                metricsCollector.setGauge("workflow.instances.completed", completedCount.toDouble())
                metricsCollector.setGauge("workflow.instances.failed", failedCount.toDouble())
            }
        }
    }
}
