package com.hftdc.journal

import com.hftdc.model.OrderBookSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * 基于文件的日志服务实现
 */
class FileJournalService(
    private val baseDir: String,
    private val snapshotDir: String,
    private val flushIntervalMs: Long = 1000, // 每秒刷新一次
    private val snapshotIntervalMs: Long = 0 // 0表示不自动创建快照
) : JournalService {
    
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    private val eventBuffer = mutableListOf<JournalEvent>()
    private val eventIdCounter = AtomicLong(0)
    private val instrumentSnapshots = ConcurrentHashMap<String, MutableList<InstrumentSnapshot>>()
    
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    
    init {
        // 创建必要的目录
        createDirectories()
        
        // 加载现有快照
        loadExistingSnapshots()
        
        // 设置定期刷新
        if (flushIntervalMs > 0) {
            scheduler.scheduleAtFixedRate(
                { flushEvents() },
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS
            )
        }
        
        logger.info { "文件日志服务已初始化，基础目录: $baseDir, 快照目录: $snapshotDir" }
    }
    
    /**
     * 创建必要的目录结构
     */
    private fun createDirectories() {
        try {
            Files.createDirectories(Paths.get(baseDir))
            Files.createDirectories(Paths.get(snapshotDir))
            logger.debug { "创建日志目录: $baseDir, $snapshotDir" }
        } catch (e: Exception) {
            logger.error(e) { "创建日志目录失败" }
            throw RuntimeException("无法创建日志目录", e)
        }
    }
    
    /**
     * 加载现有快照
     */
    private fun loadExistingSnapshots() {
        try {
            val snapshotDirFile = File(snapshotDir)
            if (!snapshotDirFile.exists()) return
            
            snapshotDirFile.listFiles { file -> file.isDirectory }?.forEach { instrumentDir ->
                val instrumentId = instrumentDir.name
                
                instrumentDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.forEach { snapshotFile ->
                    try {
                        val content = snapshotFile.readText()
                        val snapshot = json.decodeFromString<InstrumentSnapshot>(content)
                        
                        instrumentSnapshots.computeIfAbsent(instrumentId) { mutableListOf() }.add(snapshot)
                        
                        logger.debug { "加载快照: ${snapshot.id}, 品种: ${snapshot.instrumentId}, 时间: ${snapshot.timestamp}" }
                    } catch (e: Exception) {
                        logger.error(e) { "解析快照文件失败: ${snapshotFile.absolutePath}" }
                    }
                }
            }
            
            // 为每个品种只保留最新的快照（内存中）
            instrumentSnapshots.forEach { (instrumentId, snapshots) ->
                val latestSnapshot = snapshots.maxByOrNull { it.timestamp }
                if (latestSnapshot != null) {
                    instrumentSnapshots[instrumentId] = mutableListOf(latestSnapshot)
                    logger.info { "为品种 $instrumentId 加载了最新快照, 时间戳: ${latestSnapshot.timestamp}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "加载现有快照失败" }
        }
    }
    
    /**
     * 记录事件
     */
    override fun journal(event: JournalEvent) {
        synchronized(eventBuffer) {
            eventBuffer.add(event)
        }
        
        // 如果缓冲区超过一定大小，立即刷新
        if (eventBuffer.size >= 1000) {
            flushEvents()
        }
    }
    
    /**
     * 将缓冲事件写入文件
     */
    private fun flushEvents() {
        val eventsToFlush: List<JournalEvent>
        
        synchronized(eventBuffer) {
            if (eventBuffer.isEmpty()) return
            eventsToFlush = ArrayList(eventBuffer)
            eventBuffer.clear()
        }
        
        try {
            // 按照事件类型和品种分组
            val eventsByTypeAndInstrument = eventsToFlush.groupBy { event ->
                val instrumentId = when (event) {
                    is OrderSubmittedEvent -> event.order.instrumentId
                    is OrderCanceledEvent -> event.instrumentId ?: "unknown"
                    is TradeCreatedEvent -> event.trade.instrumentId
                    is SnapshotCreatedEvent -> event.instrumentId
                    else -> "unknown"
                }
                
                Pair(event.type, instrumentId)
            }
            
            // 为每个组写入单独的文件
            eventsByTypeAndInstrument.forEach { (typeAndInstrument, events) ->
                val (eventType, instrumentId) = typeAndInstrument
                
                val timestamp = Instant.now()
                val dateTime = LocalDateTime.ofInstant(timestamp, ZoneId.systemDefault())
                val dateStr = DateTimeFormatter.ofPattern("yyyyMMdd").format(dateTime)
                val timeStr = DateTimeFormatter.ofPattern("HHmmss").format(dateTime)
                
                val dir = Paths.get(baseDir, instrumentId, eventType.name, dateStr).toFile()
                dir.mkdirs()
                
                val filename = "${timeStr}-${System.nanoTime()}.json"
                val file = File(dir, filename)
                
                val jsonContent = json.encodeToString(events)
                file.writeText(jsonContent)
                
                logger.debug { "已将 ${events.size} 个 $eventType 事件写入文件: ${file.absolutePath}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "刷新事件到文件失败" }
            
            // 出错时，将事件放回缓冲区
            synchronized(eventBuffer) {
                eventBuffer.addAll(0, eventsToFlush)
            }
        }
    }
    
    /**
     * 保存快照
     */
    override fun saveSnapshot(instrumentId: String, snapshot: OrderBookSnapshot): String {
        val snapshotId = "snapshot-${System.nanoTime()}"
        val timestamp = Instant.now().toEpochMilli()
        
        val instrumentSnapshot = InstrumentSnapshot(
            id = snapshotId,
            instrumentId = instrumentId,
            timestamp = timestamp,
            snapshot = snapshot
        )
        
        try {
            // 更新内存中的快照
            instrumentSnapshots.computeIfAbsent(instrumentId) { mutableListOf() }.add(instrumentSnapshot)
            
            // 写入快照到文件
            val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
            val dateStr = DateTimeFormatter.ofPattern("yyyyMMdd").format(dateTime)
            
            val dir = Paths.get(snapshotDir, instrumentId, dateStr).toFile()
            dir.mkdirs()
            
            val filename = "${snapshotId}.json"
            val file = File(dir, filename)
            
            val jsonContent = json.encodeToString(instrumentSnapshot)
            file.writeText(jsonContent)
            
            // 记录快照创建事件
            val snapshotEvent = SnapshotCreatedEvent(
                eventId = eventIdCounter.incrementAndGet(),
                timestamp = timestamp,
                instrumentId = instrumentId,
                snapshotId = snapshotId
            )
            
            journal(snapshotEvent)
            
            logger.debug { "已保存品种 $instrumentId 的快照，ID: $snapshotId" }
            
            return snapshotId
        } catch (e: Exception) {
            logger.error(e) { "保存快照失败: $instrumentId" }
            throw RuntimeException("保存快照失败", e)
        }
    }
    
    /**
     * 获取最新快照
     */
    override fun getLatestSnapshot(instrumentId: String): InstrumentSnapshot? {
        return instrumentSnapshots[instrumentId]?.maxByOrNull { it.timestamp }
    }
    
    /**
     * 获取指定时间戳之后的所有事件
     */
    override fun getEventsSince(instrumentId: String, timestamp: Long): List<JournalEvent> {
        return getEventsFromFiles(instrumentId, timestamp, Long.MAX_VALUE)
    }
    
    /**
     * 获取指定时间范围内的所有事件
     */
    override fun getEventsBetween(instrumentId: String, fromTimestamp: Long, toTimestamp: Long): List<JournalEvent> {
        return getEventsFromFiles(instrumentId, fromTimestamp, toTimestamp)
    }
    
    /**
     * 从文件中读取事件
     */
    private fun getEventsFromFiles(instrumentId: String, startTime: Long, endTime: Long): List<JournalEvent> {
        val result = mutableListOf<JournalEvent>()
        
        try {
            // 确保内存中的事件先刷新到文件
            flushEvents()
            
            // 计算日期范围
            val startDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(startTime), ZoneId.systemDefault())
            val endDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(endTime), ZoneId.systemDefault())
            
            val startDateStr = DateTimeFormatter.ofPattern("yyyyMMdd").format(startDate)
            val endDateStr = DateTimeFormatter.ofPattern("yyyyMMdd").format(endDate)
            
            // 处理所有事件类型
            for (eventType in EventType.values()) {
                val typeDir = Paths.get(baseDir, instrumentId, eventType.name).toFile()
                if (!typeDir.exists()) continue
                
                // 列出该类型下的所有日期目录
                typeDir.listFiles { file -> 
                    file.isDirectory && file.name >= startDateStr && file.name <= endDateStr 
                }?.forEach { dateDir ->
                    // 读取每个日期目录下的所有事件文件
                    dateDir.listFiles { file -> 
                        file.isFile && file.name.endsWith(".json")
                    }?.forEach { eventFile ->
                        try {
                            val content = eventFile.readText()
                            val events = json.decodeFromString<List<JournalEvent>>(content)
                            
                            // 过滤出时间范围内的事件
                            val filteredEvents = events.filter { 
                                it.timestamp >= startTime && it.timestamp <= endTime 
                            }
                            
                            result.addAll(filteredEvents)
                        } catch (e: SerializationException) {
                            logger.error(e) { "解析事件文件失败: ${eventFile.absolutePath}" }
                        }
                    }
                }
            }
            
            // 按时间戳排序
            return result.sortedBy { it.timestamp }
        } catch (e: Exception) {
            logger.error(e) { "读取事件失败: $instrumentId" }
            return emptyList()
        }
    }
    
    /**
     * Or return all instruments with snapshots
     */
    override fun getInstrumentsWithSnapshots(): Set<String> {
        return instrumentSnapshots.keys
    }
    
    /**
     * 关闭日志服务
     */
    override fun shutdown() {
        logger.info { "关闭文件日志服务..." }
        
        try {
            // 停止调度器
            scheduler.shutdown()
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
            
            // 确保所有事件都被刷新到文件
            flushEvents()
        } catch (e: Exception) {
            logger.error(e) { "关闭文件日志服务时发生错误" }
            scheduler.shutdownNow()
        }
    }
} 