package com.hftdc.journal

import com.hftdc.model.OrderBookSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import mu.KotlinLogging
import java.io.BufferedWriter
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Collectors

private val logger = KotlinLogging.logger {}

/**
 * 日志服务接口 - 处理系统中所有事件的日志记录
 */
interface JournalService {
    /**
     * 记录事件
     */
    fun journal(event: JournalEvent)
    
    /**
     * 记录快照
     */
    fun saveSnapshot(instrumentId: String, snapshot: OrderBookSnapshot): String
    
    /**
     * 获取最新快照
     */
    fun getLatestSnapshot(instrumentId: String): JournalSnapshot?
    
    /**
     * 获取所有快照的品种ID
     */
    fun getSnapshotInstrumentIds(): Set<String>
    
    /**
     * 获取指定时间范围内的事件
     */
    fun getEvents(
        instrumentId: String,
        startTime: Long,
        endTime: Long,
        types: Set<EventType>? = null
    ): List<JournalEvent>
    
    /**
     * 关闭日志服务
     */
    fun shutdown()
}

/**
 * 基于文件的日志服务实现
 */
class FileJournalService(
    private val baseDir: String = "data/journal",
    private val snapshotDir: String = "data/snapshots",
    private val flushIntervalMs: Long = 1000, // 刷新到磁盘的间隔
    private val snapshotIntervalMs: Long = 60000 // 快照间隔
) : JournalService {
    
    // JSON序列化配置
    private val jsonModule = SerializersModule {
        polymorphic(JournalEvent::class) {
            subclass(OrderSubmittedEvent::class)
            subclass(OrderAcceptedEvent::class)
            subclass(OrderRejectedEvent::class)
            subclass(OrderExecutedEvent::class)
            subclass(OrderCanceledEvent::class)
            subclass(TradeCreatedEvent::class)
            subclass(SnapshotCreatedEvent::class)
        }
    }
    
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        serializersModule = jsonModule
    }
    
    // 事件计数器，用于生成唯一ID
    private val eventIdCounter = AtomicLong(0)
    
    // 写入器映射，每个品种一个写入器
    private val writers = ConcurrentHashMap<String, BufferedWriter>()
    
    // 事件队列，异步写入
    private val eventQueue = LinkedBlockingQueue<Pair<String, String>>()
    
    // 最新的快照缓存
    private val latestSnapshots = ConcurrentHashMap<String, JournalSnapshot>()
    
    // 线程池
    private val executorService = Executors.newSingleThreadExecutor()
    private val scheduledService: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    
    init {
        // 创建目录
        createDirectories()
        
        // 启动异步写入线程
        startAsyncWriter()
        
        // 启动定期刷新任务
        startPeriodicFlush()
        
        logger.info { "文件日志服务已启动，基础目录: $baseDir, 快照目录: $snapshotDir" }
    }
    
    /**
     * 创建必要的目录
     */
    private fun createDirectories() {
        Files.createDirectories(Paths.get(baseDir))
        Files.createDirectories(Paths.get(snapshotDir))
    }
    
    /**
     * 启动异步写入线程
     */
    private fun startAsyncWriter() {
        executorService.submit {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val (instrumentId, eventJson) = eventQueue.take()
                    try {
                        val writer = getWriter(instrumentId)
                        writer.write(eventJson)
                        writer.newLine()
                    } catch (e: Exception) {
                        logger.error(e) { "写入事件到日志文件失败: $instrumentId" }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.info { "异步写入线程被中断" }
            } catch (e: Exception) {
                logger.error(e) { "异步写入线程异常" }
            }
        }
    }
    
    /**
     * 启动定期刷新任务
     */
    private fun startPeriodicFlush() {
        scheduledService.scheduleAtFixedRate(
            { flushAll() },
            flushIntervalMs,
            flushIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }
    
    /**
     * 获取指定品种的写入器
     */
    private fun getWriter(instrumentId: String): BufferedWriter {
        return writers.computeIfAbsent(instrumentId) {
            val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val filePath = Paths.get(baseDir, "${instrumentId}_$date.log")
            FileWriter(filePath.toFile(), true).buffered()
        }
    }
    
    /**
     * 刷新所有写入器
     */
    private fun flushAll() {
        writers.forEach { (_, writer) ->
            try {
                writer.flush()
            } catch (e: Exception) {
                logger.error(e) { "刷新写入器失败" }
            }
        }
    }
    
    /**
     * 关闭所有写入器
     */
    private fun closeAllWriters() {
        writers.forEach { (_, writer) ->
            try {
                writer.close()
            } catch (e: Exception) {
                logger.error(e) { "关闭写入器失败" }
            }
        }
        writers.clear()
    }
    
    override fun journal(event: JournalEvent) {
        try {
            // 获取品种ID
            val instrumentId = when (event) {
                is OrderSubmittedEvent -> event.order.instrumentId
                is OrderAcceptedEvent -> event.instrumentId
                is OrderRejectedEvent -> "system" // 系统级别事件
                is OrderExecutedEvent -> "system" // 需要从其他地方获取instrumentId
                is OrderCanceledEvent -> "system" // 需要从其他地方获取instrumentId
                is TradeCreatedEvent -> event.trade.instrumentId
                is SnapshotCreatedEvent -> event.instrumentId
                else -> "system"
            }
            
            // 序列化事件
            val eventJson = json.encodeToString(event)
            
            // 异步写入队列
            eventQueue.offer(instrumentId to eventJson)
        } catch (e: Exception) {
            logger.error(e) { "记录事件失败: $event" }
        }
    }
    
    override fun saveSnapshot(instrumentId: String, snapshot: OrderBookSnapshot): String {
        try {
            // 生成唯一ID
            val snapshotId = "snapshot_${instrumentId}_${Instant.now().toEpochMilli()}"
            
            // 创建快照对象
            val journalSnapshot = JournalSnapshot(
                id = snapshotId,
                timestamp = Instant.now().toEpochMilli(),
                instrumentId = instrumentId,
                snapshot = snapshot
            )
            
            // 序列化快照
            val snapshotJson = json.encodeToString(journalSnapshot)
            
            // 写入文件
            val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val filePath = Paths.get(snapshotDir, "${instrumentId}_$date.snapshot")
            Files.write(filePath, snapshotJson.toByteArray())
            
            // 缓存最新快照
            latestSnapshots[instrumentId] = journalSnapshot
            
            // 创建快照事件
            val snapshotEvent = SnapshotCreatedEvent(
                eventId = eventIdCounter.incrementAndGet(),
                snapshotId = snapshotId,
                instrumentId = instrumentId
            )
            
            // 记录快照事件
            journal(snapshotEvent)
            
            return snapshotId
        } catch (e: Exception) {
            logger.error(e) { "保存快照失败: $instrumentId" }
            return ""
        }
    }
    
    override fun getLatestSnapshot(instrumentId: String): JournalSnapshot? {
        // 首先检查内存缓存
        latestSnapshots[instrumentId]?.let { return it }
        
        // 如果缓存中没有，从文件系统查找
        try {
            val directory = Paths.get(snapshotDir)
            if (!Files.exists(directory)) {
                return null
            }
            
            // 查找该品种的最新快照文件
            val latestFile = Files.list(directory)
                .filter { it.fileName.toString().startsWith(instrumentId) }
                .sorted { a, b -> Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)) }
                .findFirst()
                .orElse(null) ?: return null
            
            // 读取快照文件内容
            val content = Files.readString(latestFile)
            
            // 解析快照
            val snapshot = json.decodeFromString<JournalSnapshot>(content)
            
            // 更新缓存
            latestSnapshots[instrumentId] = snapshot
            
            return snapshot
        } catch (e: Exception) {
            logger.error(e) { "获取最新快照失败: $instrumentId" }
            return null
        }
    }
    
    override fun getSnapshotInstrumentIds(): Set<String> {
        // 从内存缓存获取
        val cachedIds = latestSnapshots.keys
        if (cachedIds.isNotEmpty()) {
            return cachedIds.toSet()
        }
        
        // 从文件系统获取
        try {
            val directory = Paths.get(snapshotDir)
            if (!Files.exists(directory)) {
                return emptySet()
            }
            
            return Files.list(directory)
                .map { it.fileName.toString() }
                .filter { it.endsWith(".snapshot") }
                .map { it.substringBefore("_") }
                .collect(Collectors.toSet())
        } catch (e: Exception) {
            logger.error(e) { "获取快照品种ID失败" }
            return emptySet()
        }
    }
    
    override fun getEvents(
        instrumentId: String,
        startTime: Long,
        endTime: Long,
        types: Set<EventType>?
    ): List<JournalEvent> {
        val result = mutableListOf<JournalEvent>()
        
        try {
            // 计算涉及的日期范围
            val startDate = Instant.ofEpochMilli(startTime).atZone(ZoneId.systemDefault()).toLocalDate()
            val endDate = Instant.ofEpochMilli(endTime).atZone(ZoneId.systemDefault()).toLocalDate()
            
            // 处理可能跨越多天的查询
            var currentDate = startDate
            while (!currentDate.isAfter(endDate)) {
                val dateStr = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val filePath = Paths.get(baseDir, "${instrumentId}_$dateStr.log")
                
                if (Files.exists(filePath)) {
                    Files.lines(filePath).forEach { line ->
                        try {
                            // 解析事件
                            val event = json.decodeFromString<JournalEvent>(line)
                            
                            // 检查时间范围
                            if (event.timestamp in startTime..endTime) {
                                // 检查事件类型
                                if (types == null || event.type in types) {
                                    result.add(event)
                                }
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "解析事件失败: $line" }
                        }
                    }
                }
                
                currentDate = currentDate.plusDays(1)
            }
        } catch (e: Exception) {
            logger.error(e) { "获取事件失败: $instrumentId, $startTime-$endTime" }
        }
        
        return result
    }
    
    override fun shutdown() {
        try {
            // 关闭调度器
            scheduledService.shutdown()
            if (!scheduledService.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduledService.shutdownNow()
            }
            
            // 刷新所有写入器
            flushAll()
            
            // 关闭执行器
            executorService.shutdown()
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
            }
            
            // 关闭所有写入器
            closeAllWriters()
            
            logger.info { "文件日志服务已关闭" }
        } catch (e: Exception) {
            logger.error(e) { "关闭文件日志服务失败" }
        }
    }
}

/**
 * 内存日志服务实现 - 用于测试
 */
class InMemoryJournalService : JournalService {
    private val events = ConcurrentHashMap<String, MutableList<JournalEvent>>()
    private val snapshots = ConcurrentHashMap<String, JournalSnapshot>()
    private val eventIdCounter = AtomicLong(0)
    
    // 存储订单ID到品种ID的映射，用于处理取消订单事件
    private val orderIdToInstrumentId = ConcurrentHashMap<Long, String>()
    
    override fun journal(event: JournalEvent) {
        val instrumentId = when (event) {
            is OrderSubmittedEvent -> {
                // 记录订单ID与品种ID的关系
                orderIdToInstrumentId[event.order.id] = event.order.instrumentId
                event.order.instrumentId
            }
            is OrderAcceptedEvent -> event.instrumentId
            is OrderRejectedEvent -> "system"
            is OrderExecutedEvent -> "system"
            is OrderCanceledEvent -> {
                // 对于取消订单事件，使用提供的品种ID或从映射中获取
                event.instrumentId ?: orderIdToInstrumentId[event.orderId] ?: "system"
            }
            is TradeCreatedEvent -> event.trade.instrumentId
            is SnapshotCreatedEvent -> event.instrumentId
            else -> "system"
        }
        
        println("InMemoryJournalService: 添加事件 - 类型: ${event.type}, instrumentId: $instrumentId, eventId: ${event.eventId}")
        events.computeIfAbsent(instrumentId) { mutableListOf() }.add(event)
    }
    
    override fun saveSnapshot(instrumentId: String, snapshot: OrderBookSnapshot): String {
        val snapshotId = "snapshot_${instrumentId}_${Instant.now().toEpochMilli()}"
        val journalSnapshot = JournalSnapshot(
            id = snapshotId,
            timestamp = Instant.now().toEpochMilli(),
            instrumentId = instrumentId,
            snapshot = snapshot
        )
        
        snapshots[instrumentId] = journalSnapshot
        
        val snapshotEvent = SnapshotCreatedEvent(
            eventId = eventIdCounter.incrementAndGet(),
            snapshotId = snapshotId,
            instrumentId = instrumentId
        )
        
        journal(snapshotEvent)
        
        return snapshotId
    }
    
    override fun getLatestSnapshot(instrumentId: String): JournalSnapshot? {
        return snapshots[instrumentId]
    }
    
    override fun getSnapshotInstrumentIds(): Set<String> {
        return snapshots.keys
    }
    
    override fun getEvents(
        instrumentId: String,
        startTime: Long,
        endTime: Long,
        types: Set<EventType>?
    ): List<JournalEvent> {
        println("InMemoryJournalService.getEvents: instrumentId=$instrumentId, startTime=$startTime, endTime=$endTime, types=$types")
        println("InMemoryJournalService.getEvents: 事件映射中的所有键: ${events.keys.joinToString()}")
        
        val result = events[instrumentId]
        println("InMemoryJournalService.getEvents: 找到事件列表: ${result?.size ?: 0}")
        
        val filteredResult = result?.filter { event ->
            val matchesTime = event.timestamp >= startTime && event.timestamp <= endTime
            val matchesType = types == null || event.type in types
            
            // 调试信息
            if (!matchesTime || !matchesType) {
                println("  排除事件 ${event.eventId}: timestamp=${event.timestamp}, type=${event.type}, " +
                       "matchesTime=$matchesTime (startTime=$startTime, endTime=$endTime), matchesType=$matchesType")
            }
            
            matchesTime && matchesType
        } ?: emptyList()
        
        println("InMemoryJournalService.getEvents: 过滤后事件数量: ${filteredResult.size}")
        return filteredResult
    }
    
    override fun shutdown() {
        // 清空内存中的数据
        events.clear()
        snapshots.clear()
    }
}