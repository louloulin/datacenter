package com.hftdc.core

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.javadsl.AbstractBehavior
import akka.actor.typed.javadsl.ActorContext
import akka.actor.typed.javadsl.Behaviors
import akka.actor.typed.javadsl.Receive
import com.hftdc.config.AppConfig
import com.hftdc.engine.OrderProcessor
import com.hftdc.model.Order
import mu.KotlinLogging
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Actor系统管理器 - 管理所有Actor
 */
class ActorSystemManager(private val config: AppConfig) {
    private lateinit var system: ActorSystem<RootCommand>
    
    /**
     * 启动Actor系统
     */
    fun start() {
        logger.info { "启动Actor系统..." }
        system = ActorSystem.create(RootActor.create(), "HFTDCSystem")
    }
    
    /**
     * 获取根Actor的引用
     */
    fun getRootActor(): ActorRef<RootCommand> = system
    
    /**
     * 关闭Actor系统
     */
    fun shutdown() {
        logger.info { "关闭Actor系统..." }
        system.terminate()
    }
}

/**
 * 根Actor命令接口
 */
sealed interface RootCommand

/**
 * 转发订单命令
 */
data class ForwardOrder(val order: Order) : RootCommand

/**
 * 获取系统状态命令
 */
data class GetSystemStatus(val replyTo: ActorRef<SystemStatus>) : RootCommand

/**
 * 系统状态
 */
data class SystemStatus(
    val orderBookCount: Int,
    val totalOrderCount: Int,
    val activeInstruments: List<String>
)

/**
 * 根Actor - 系统入口
 */
class RootActor private constructor(context: ActorContext<RootCommand>) : AbstractBehavior<RootCommand>(context) {
    
    // 分派Actor管理所有品种对应的订单处理Actor
    private val dispatcherActor: ActorRef<DispatcherCommand> = context.spawn(
        DispatcherActor.create(),
        "dispatcher"
    )
    
    companion object {
        fun create(): Behavior<RootCommand> {
            return Behaviors.setup { context ->
                RootActor(context)
            }
        }
    }
    
    override fun createReceive(): Receive<RootCommand> {
        return newReceiveBuilder()
            .onMessage(ForwardOrder::class.java, this::onForwardOrder)
            .onMessage(GetSystemStatus::class.java, this::onGetSystemStatus)
            .build()
    }
    
    private fun onForwardOrder(command: ForwardOrder): Behavior<RootCommand> {
        dispatcherActor.tell(RouteOrder(command.order))
        return this
    }
    
    private fun onGetSystemStatus(command: GetSystemStatus): Behavior<RootCommand> {
        // TODO: 实际收集系统状态
        val status = SystemStatus(
            orderBookCount = 0,
            totalOrderCount = 0,
            activeInstruments = emptyList()
        )
        command.replyTo.tell(status)
        return this
    }
}

/**
 * 分派Actor命令接口
 */
sealed interface DispatcherCommand

/**
 * 路由订单命令
 */
data class RouteOrder(val order: Order) : DispatcherCommand

/**
 * 处理器空闲命令 - 当处理器空闲一段时间后发送此命令
 */
data class ProcessorIdle(val instrumentId: String) : DispatcherCommand

/**
 * 分派Actor - 管理所有品种的处理Actor
 */
class DispatcherActor private constructor(context: ActorContext<DispatcherCommand>) : AbstractBehavior<DispatcherCommand>(context) {
    
    // 记录所有品种对应的处理器Actor引用
    private val instrumentProcessors = mutableMapOf<String, ActorRef<ProcessorCommand>>()
    
    companion object {
        fun create(): Behavior<DispatcherCommand> {
            return Behaviors.setup { context ->
                DispatcherActor(context)
            }
        }
    }
    
    override fun createReceive(): Receive<DispatcherCommand> {
        return newReceiveBuilder()
            .onMessage(RouteOrder::class.java, this::onRouteOrder)
            .onMessage(ProcessorIdle::class.java, this::onProcessorIdle)
            .build()
    }
    
    private fun onRouteOrder(command: RouteOrder): Behavior<DispatcherCommand> {
        val instrumentId = command.order.instrumentId
        val processor = getOrCreateProcessor(instrumentId)
        processor.tell(ProcessOrder(command.order))
        return this
    }
    
    private fun onProcessorIdle(command: ProcessorIdle): Behavior<DispatcherCommand> {
        val instrumentId = command.instrumentId
        instrumentProcessors[instrumentId]?.let { processor ->
            context.stop(processor)
            instrumentProcessors.remove(instrumentId)
            logger.info { "移除空闲处理器: $instrumentId" }
        }
        return this
    }
    
    private fun getOrCreateProcessor(instrumentId: String): ActorRef<ProcessorCommand> {
        return instrumentProcessors.getOrPut(instrumentId) {
            context.spawn(
                InstrumentProcessorActor.create(instrumentId),
                "processor-$instrumentId"
            )
        }
    }
}

/**
 * 处理器Actor命令接口
 */
sealed interface ProcessorCommand

/**
 * 处理订单命令
 */
data class ProcessOrder(val order: Order) : ProcessorCommand

/**
 * 检查空闲状态命令
 */
object CheckIdleTimeout : ProcessorCommand

/**
 * 处理器Actor - 处理单个品种的订单
 */
class InstrumentProcessorActor private constructor(
    context: ActorContext<ProcessorCommand>,
    private val instrumentId: String
) : AbstractBehavior<ProcessorCommand>(context) {
    
    // 上次活动时间
    private var lastActivityTime = System.currentTimeMillis()
    
    // 超时检查定时器
    private val timerId = "idle-timer"
    
    init {
        // 设置空闲超时检查
        context.scheduleOnce(Duration.ofMinutes(30), context.self, CheckIdleTimeout)
    }
    
    companion object {
        fun create(instrumentId: String): Behavior<ProcessorCommand> {
            return Behaviors.setup { context ->
                InstrumentProcessorActor(context, instrumentId)
            }
        }
    }
    
    override fun createReceive(): Receive<ProcessorCommand> {
        return newReceiveBuilder()
            .onMessage(ProcessOrder::class.java, this::onProcessOrder)
            .onMessage(CheckIdleTimeout::class.java) { _ -> onCheckIdleTimeout() }
            .build()
    }
    
    private fun onProcessOrder(command: ProcessOrder): Behavior<ProcessorCommand> {
        logger.debug { "处理器 $instrumentId 接收到订单: ${command.order.id}" }
        
        // 更新活动时间
        lastActivityTime = System.currentTimeMillis()
        
        // TODO: 实际处理订单
        
        return this
    }
    
    private fun onCheckIdleTimeout(): Behavior<ProcessorCommand> {
        val currentTime = System.currentTimeMillis()
        val idleTime = currentTime - lastActivityTime
        
        // 如果空闲超过30分钟，通知分派器销毁此处理器
        if (idleTime > 30 * 60 * 1000) {
            logger.debug { "处理器 $instrumentId 空闲超过30分钟，准备关闭" }
            // 由于我们无法直接访问parent，这里只记录日志
            // 在实际实现中，应该通过专门的管理Actor或事件来处理
            logger.info { "处理器 $instrumentId 请求关闭" }
        } else {
            // 重新安排检查
            context.scheduleOnce(Duration.ofMinutes(30), context.self, CheckIdleTimeout)
        }
        
        return this
    }
} 