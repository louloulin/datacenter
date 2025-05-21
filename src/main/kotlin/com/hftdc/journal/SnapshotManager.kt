package com.hftdc.journal

import com.hftdc.engine.OrderBookManager
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * 快照管理器 - 负责定期创建系统状态快照
 */
class SnapshotManager(
    private val journalService: JournalService,
    private val orderBookManager: OrderBookManager,
    private val snapshotIntervalMs: Long = 60000, // 默认60秒
    private val initialDelayMs: Long = 10000, // 默认10秒后开始
    private val snapshotDepth: Int = 10 // 订单簿快照深度
) {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val snapshotCounter = AtomicLong(0)
    private var isRunning = false
    
    /**
     * 启动定期快照任务
     */
    fun start() {
        if (isRunning) {
            logger.warn { "快照管理器已经在运行中" }
            return
        }
        
        isRunning = true
        scheduler.scheduleAtFixedRate(
            { createSnapshots() },
            initialDelayMs,
            snapshotIntervalMs,
            TimeUnit.MILLISECONDS
        )
        
        logger.info { "快照管理器已启动，间隔: ${snapshotIntervalMs}ms" }
    }
    
    /**
     * 停止定期快照任务
     */
    fun stop() {
        if (!isRunning) {
            logger.warn { "快照管理器未在运行" }
            return
        }
        
        isRunning = false
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        logger.info { "快照管理器已停止" }
    }
    
    /**
     * 为所有活跃的订单簿创建快照
     */
    fun createSnapshots() {
        try {
            val startTime = Instant.now()
            val activeInstruments = orderBookManager.getActiveInstrumentIds()
            var successCount = 0
            
            logger.info { "开始为 ${activeInstruments.size} 个活跃品种创建快照..." }
            
            activeInstruments.forEach { instrumentId ->
                try {
                    val orderBook = orderBookManager.getOrderBook(instrumentId)
                    val snapshot = orderBook.getSnapshot(snapshotDepth)
                    val snapshotId = journalService.saveSnapshot(instrumentId, snapshot)
                    
                    logger.debug { "成功创建快照: $instrumentId, 快照ID: $snapshotId" }
                    successCount++
                } catch (e: Exception) {
                    logger.error(e) { "为品种 $instrumentId 创建快照失败" }
                }
            }
            
            val elapsedMs = Instant.now().toEpochMilli() - startTime.toEpochMilli()
            val totalSnapshots = snapshotCounter.addAndGet(successCount.toLong())
            
            logger.info { "快照创建完成: 总共 ${activeInstruments.size} 个品种, 成功 $successCount 个, 耗时 ${elapsedMs}ms, 累计快照数: $totalSnapshots" }
        } catch (e: Exception) {
            logger.error(e) { "创建快照过程中发生错误" }
        }
    }
    
    /**
     * 为指定品种创建快照
     */
    fun createSnapshotFor(instrumentId: String): String? {
        return try {
            val orderBook = orderBookManager.getOrderBook(instrumentId)
            val snapshot = orderBook.getSnapshot(snapshotDepth)
            val snapshotId = journalService.saveSnapshot(instrumentId, snapshot)
            
            logger.debug { "手动创建快照: $instrumentId, 快照ID: $snapshotId" }
            snapshotCounter.incrementAndGet()
            
            snapshotId
        } catch (e: Exception) {
            logger.error(e) { "为品种 $instrumentId 手动创建快照失败" }
            null
        }
    }
    
    /**
     * 获取累计创建的快照数量
     */
    fun getSnapshotCount(): Long = snapshotCounter.get()
} 