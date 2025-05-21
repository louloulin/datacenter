package com.hftdc.disruptorx.integration

import com.hftdc.disruptorx.DisruptorXNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * 故障注入器
 * 用于模拟各种故障场景，测试系统容错能力
 */
class FaultInjector(
    private val coroutineContext: CoroutineContext = Dispatchers.Default
) {
    // 节点注册表
    private val nodes = ConcurrentHashMap<String, NodeWithFaults>()
    
    /**
     * 注册一个节点
     * @param nodeId 节点ID
     * @param node 节点实例
     * @return 当前故障注入器实例，用于链式调用
     */
    fun registerNode(nodeId: String, node: DisruptorXNode): FaultInjector {
        nodes[nodeId] = NodeWithFaults(nodeId, node)
        return this
    }
    
    /**
     * 注册多个节点
     * @param nodeMap 节点映射表
     * @return 当前故障注入器实例，用于链式调用
     */
    fun registerNodes(nodeMap: Map<String, DisruptorXNode>): FaultInjector {
        nodeMap.forEach { (nodeId, node) ->
            registerNode(nodeId, node)
        }
        return this
    }
    
    /**
     * 杀死指定节点（关闭并重新启动）
     * @param nodeId 节点ID
     * @param downTimeMs 节点关闭时间（毫秒）
     */
    suspend fun killNode(nodeId: String, downTimeMs: Long = 5000) {
        val nodeWrapper = getNodeOrThrow(nodeId)
        
        // 先停止节点
        println("故障注入: 关闭节点 $nodeId")
        withContext(coroutineContext) {
            nodeWrapper.node.shutdown()
            
            // 等待指定的宕机时间
            delay(downTimeMs)
            
            // 重新启动节点
            println("故障注入: 重启节点 $nodeId")
            nodeWrapper.node.initialize()
        }
    }
    
    /**
     * 注入网络断开故障
     * @param nodeId 节点ID
     * @param durationMs 故障持续时间（毫秒）
     */
    suspend fun disconnectNetwork(nodeId: String, durationMs: Long = 5000) {
        val nodeWrapper = getNodeOrThrow(nodeId)
        
        // 启用网络断开模拟
        println("故障注入: 断开节点 $nodeId 的网络连接")
        nodeWrapper.networkDisconnected = true
        
        // 等待指定的故障持续时间
        delay(durationMs)
        
        // 恢复网络连接
        println("故障注入: 恢复节点 $nodeId 的网络连接")
        nodeWrapper.networkDisconnected = false
    }
    
    /**
     * 注入网络延迟故障
     * @param nodeId 节点ID
     * @param delayMs 延迟时间（毫秒）
     * @param durationMs 故障持续时间（毫秒）
     */
    suspend fun slowDownNetwork(nodeId: String, delayMs: Long = 200, durationMs: Long = 5000) {
        val nodeWrapper = getNodeOrThrow(nodeId)
        
        // 启用网络延迟模拟
        println("故障注入: 降低节点 $nodeId 的网络速度，延迟 $delayMs ms")
        nodeWrapper.networkDelayMs = delayMs
        
        // 等待指定的故障持续时间
        delay(durationMs)
        
        // 恢复正常网络速度
        println("故障注入: 恢复节点 $nodeId 的网络速度")
        nodeWrapper.networkDelayMs = 0
    }
    
    /**
     * 注入消息丢失故障
     * @param nodeId 节点ID
     * @param lossRate 丢包率（0.0-1.0）
     * @param durationMs 故障持续时间（毫秒）
     */
    suspend fun injectMessageLoss(nodeId: String, lossRate: Double = 0.3, durationMs: Long = 5000) {
        val nodeWrapper = getNodeOrThrow(nodeId)
        
        // 启用消息丢失模拟
        println("故障注入: 在节点 $nodeId 上模拟消息丢失，丢包率 ${lossRate * 100}%")
        nodeWrapper.messageLossRate = lossRate
        
        // 等待指定的故障持续时间
        delay(durationMs)
        
        // 恢复正常消息传输
        println("故障注入: 停止节点 $nodeId 上的消息丢失模拟")
        nodeWrapper.messageLossRate = 0.0
    }
    
    /**
     * 注入CPU负载故障
     * @param nodeId 节点ID
     * @param loadPercentage CPU负载百分比（0-100）
     * @param durationMs 故障持续时间（毫秒）
     */
    suspend fun injectCpuLoad(nodeId: String, loadPercentage: Int = 80, durationMs: Long = 5000) {
        val nodeWrapper = getNodeOrThrow(nodeId)
        
        // 启动高CPU负载线程
        println("故障注入: 在节点 $nodeId 上产生 $loadPercentage% 的CPU负载")
        val loadThread = Thread {
            val endTime = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < endTime) {
                // 根据负载百分比决定计算时间和休眠时间
                val computeTime = loadPercentage
                val sleepTime = 100 - loadPercentage
                
                // 执行计算密集型操作
                val startCompute = System.currentTimeMillis()
                while (System.currentTimeMillis() - startCompute < computeTime) {
                    // 执行一些无意义的计算
                    val random = ThreadLocalRandom.current()
                    var result = 0.0
                    for (i in 0 until 10000) {
                        result += Math.sin(random.nextDouble()) * Math.cos(random.nextDouble())
                    }
                }
                
                // 短暂休眠
                if (sleepTime > 0) {
                    TimeUnit.MILLISECONDS.sleep(sleepTime.toLong())
                }
            }
        }
        loadThread.name = "CPU-Load-Injector-$nodeId"
        loadThread.isDaemon = true
        loadThread.start()
        
        // 等待线程完成
        delay(durationMs + 500)
        println("故障注入: 停止节点 $nodeId 上的CPU负载")
    }
    
    /**
     * 获取节点包装器或抛出异常
     */
    private fun getNodeOrThrow(nodeId: String): NodeWithFaults {
        return nodes[nodeId] ?: throw IllegalArgumentException("未注册的节点ID: $nodeId")
    }
    
    /**
     * 带故障模拟的节点包装器
     */
    private class NodeWithFaults(
        val nodeId: String,
        val node: DisruptorXNode
    ) {
        // 故障模拟标志
        @Volatile var networkDisconnected: Boolean = false
        @Volatile var networkDelayMs: Long = 0
        @Volatile var messageLossRate: Double = 0.0
        
        /**
         * 模拟网络传输
         * @return 是否应该传输消息
         */
        fun shouldTransmitMessage(): Boolean {
            // 如果网络已断开，则不传输
            if (networkDisconnected) return false
            
            // 根据丢包率决定是否丢弃消息
            if (messageLossRate > 0) {
                if (ThreadLocalRandom.current().nextDouble() < messageLossRate) {
                    return false
                }
            }
            
            // 模拟网络延迟
            if (networkDelayMs > 0) {
                try {
                    TimeUnit.MILLISECONDS.sleep(networkDelayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            
            return true
        }
    }
} 