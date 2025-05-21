package com.hftdc.risk

import com.hftdc.model.Order
import com.hftdc.model.OrderSide
import mu.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val logger = KotlinLogging.logger {}

/**
 * 风险管理器 - 负责风险检查和限制
 */
class RiskManager(
    configPath: String = "risk-rules.conf"
) {
    // 风险规则集合，按优先级排序
    private val rules = mutableListOf<RiskRule>()
    
    // 用户风险限制
    private val userRiskLimits = ConcurrentHashMap<Long, UserRiskLimits>()
    
    // 品种风险参数
    private val instrumentRiskParams = ConcurrentHashMap<String, InstrumentRiskParams>()
    
    // 用户交易速率计数器
    private val userOrderRates = ConcurrentHashMap<Long, SlidingWindowRateLimiter>()
    
    // 用户持仓
    private val userPositions = ConcurrentHashMap<Long, ConcurrentHashMap<String, Long>>()
    
    // 用户未成交订单计数
    private val userPendingOrders = ConcurrentHashMap<Long, ConcurrentHashMap<String, ConcurrentHashMap<OrderSide, AtomicInteger>>>()
    
    // 读写锁，用于保护规则列表
    private val rulesLock = ReentrantReadWriteLock()
    
    // 调度器，用于定期任务
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    
    init {
        // 加载风险规则
        loadRiskRules(configPath)
        
        // 启动定期任务
        startPeriodicTasks()
        
        logger.info { "风险管理器已启动" }
    }
    
    /**
     * 加载风险规则
     */
    private fun loadRiskRules(configPath: String) {
        // 这里应该从配置文件加载规则
        // 简单起见，先添加一些默认规则
        rulesLock.write {
            rules.add(AccountStatusRule())
            rules.add(BalanceCheckRule())
            rules.add(OrderSizeRule())
            rules.add(PositionLimitRule())
            rules.add(RateLimitRule())
            
            // 按优先级排序
            rules.sortBy { it.priority }
        }
    }
    
    /**
     * 启动定期任务
     */
    private fun startPeriodicTasks() {
        // 每天清理过期数据
        scheduler.scheduleAtFixedRate(
            { cleanupExpiredData() },
            1,
            24,
            TimeUnit.HOURS
        )
    }
    
    /**
     * 清理过期数据
     */
    private fun cleanupExpiredData() {
        try {
            // 清理长时间不活跃的用户的速率限制器
            val now = Instant.now()
            userOrderRates.entries.removeIf { (_, limiter) ->
                Duration.between(limiter.getLastUpdateTime(), now).toHours() > 24
            }
            
            logger.info { "已清理过期数据" }
        } catch (e: Exception) {
            logger.error(e) { "清理过期数据时发生错误" }
        }
    }
    
    /**
     * 检查订单是否通过风险控制
     */
    fun checkOrder(order: Order): RiskCheckResult {
        try {
            // 创建风险上下文
            val context = createRiskContext(order.userId)
            
            // 执行所有风险规则检查
            rulesLock.read {
                for (rule in rules) {
                    val result = rule.check(context, order)
                    if (!result.passed) {
                        logger.warn { "订单 ${order.id} 未通过风险规则 ${rule.name}: ${result.reason}" }
                        return result
                    }
                }
            }
            
            // 所有规则都通过
            return RiskCheckResult(passed = true, timestamp = System.currentTimeMillis())
        } catch (e: Exception) {
            logger.error(e) { "执行风险检查时发生错误: ${order.id}" }
            return RiskCheckResult(
                passed = false,
                reason = "风险检查过程中发生错误: ${e.message}",
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * 创建风险上下文
     */
    private fun createRiskContext(userId: Long): RiskContext {
        return RiskContext(
            userId = userId,
            accountIds = getAccountIds(userId),
            getAvailableBalance = { currency -> getAvailableBalance(userId, currency) },
            getPosition = { instrumentId -> getPosition(userId, instrumentId) },
            getPendingOrdersCount = { instrumentId, side -> getPendingOrdersCount(userId, instrumentId, side) },
            getOrderRate = { getOrderRate(userId) },
            getRiskLevel = { getRiskLevel(userId) },
            getInstrumentRiskParams = { instrumentId -> getInstrumentRiskParams(instrumentId) }
        )
    }
    
    /**
     * 获取用户的账户ID列表
     */
    private fun getAccountIds(userId: Long): List<Long> {
        // 这里应该从数据库或缓存中获取
        // 简单起见，假设每个用户只有一个与用户ID相同的账户
        return listOf(userId)
    }
    
    /**
     * 获取用户可用余额
     */
    private fun getAvailableBalance(userId: Long, currency: String): Long {
        // 这里应该从数据库或缓存中获取
        // 简单起见，返回一个固定值
        return 1_000_000_000 // 10亿, 单位为最小单位
    }
    
    /**
     * 获取用户持仓
     */
    private fun getPosition(userId: Long, instrumentId: String): Long {
        return userPositions
            .getOrPut(userId) { ConcurrentHashMap() }
            .getOrDefault(instrumentId, 0)
    }
    
    /**
     * 获取用户未成交订单数量
     */
    private fun getPendingOrdersCount(userId: Long, instrumentId: String, side: OrderSide): Long {
        return userPendingOrders
            .getOrPut(userId) { ConcurrentHashMap() }
            .getOrPut(instrumentId) { ConcurrentHashMap() }
            .getOrPut(side) { AtomicInteger(0) }
            .get().toLong()
    }
    
    /**
     * 获取用户订单频率
     */
    private fun getOrderRate(userId: Long): Double {
        return userOrderRates
            .getOrPut(userId) { SlidingWindowRateLimiter(windowSizeMillis = 1000, maxEvents = 100) }
            .getCurrentRate()
    }
    
    /**
     * 获取用户风险级别
     */
    private fun getRiskLevel(userId: Long): RiskLevel {
        return userRiskLimits
            .getOrPut(userId) { createDefaultUserRiskLimits(userId) }
            .riskLevel
    }
    
    /**
     * 获取品种风险参数
     */
    private fun getInstrumentRiskParams(instrumentId: String): InstrumentRiskParams {
        return instrumentRiskParams
            .getOrPut(instrumentId) { createDefaultInstrumentRiskParams(instrumentId) }
    }
    
    /**
     * 创建默认用户风险限制
     */
    private fun createDefaultUserRiskLimits(userId: Long): UserRiskLimits {
        return UserRiskLimits(
            userId = userId,
            maxOrderAmount = 100_000_000, // 1百万
            maxDailyAmount = 1_000_000_000, // 1千万
            maxPositionPerInstrument = 100_000_000, // 1百万
            maxPendingOrders = 100,
            maxOrdersPerSecond = 10,
            riskLevel = RiskLevel.LOW
        )
    }
    
    /**
     * 创建默认品种风险参数
     */
    private fun createDefaultInstrumentRiskParams(instrumentId: String): InstrumentRiskParams {
        return InstrumentRiskParams(
            instrumentId = instrumentId,
            maxOrderSize = 10_000_000, // 10万
            minOrderSize = 100,
            priceFluctLimit = 0.1, // 10%
            marginRequirement = 0.1, // 10%
            maxLeverage = 10,
            allowShort = true,
            maxPositionSize = 100_000_000, // 1百万
            maxOrdersPerSecond = 100
        )
    }
    
    /**
     * 更新用户下单速率
     */
    fun recordOrderEvent(userId: Long) {
        userOrderRates
            .getOrPut(userId) { SlidingWindowRateLimiter(windowSizeMillis = 1000, maxEvents = 100) }
            .recordEvent()
    }
    
    /**
     * 更新用户持仓
     */
    fun updatePosition(userId: Long, instrumentId: String, deltaQuantity: Long) {
        userPositions
            .getOrPut(userId) { ConcurrentHashMap() }
            .compute(instrumentId) { _, current -> (current ?: 0) + deltaQuantity }
    }
    
    /**
     * 更新用户未成交订单计数
     */
    fun updatePendingOrdersCount(userId: Long, instrumentId: String, side: OrderSide, deltaCnt: Int) {
        userPendingOrders
            .getOrPut(userId) { ConcurrentHashMap() }
            .getOrPut(instrumentId) { ConcurrentHashMap() }
            .getOrPut(side) { AtomicInteger(0) }
            .addAndGet(deltaCnt)
    }
    
    /**
     * 设置用户风险限制
     */
    fun setUserRiskLimits(limits: UserRiskLimits) {
        userRiskLimits[limits.userId] = limits
    }
    
    /**
     * 设置品种风险参数
     */
    fun setInstrumentRiskParams(params: InstrumentRiskParams) {
        instrumentRiskParams[params.instrumentId] = params
    }
    
    /**
     * 添加风险规则
     */
    fun addRiskRule(rule: RiskRule) {
        rulesLock.write {
            rules.add(rule)
            rules.sortBy { it.priority }
        }
    }
    
    /**
     * 移除风险规则
     */
    fun removeRiskRule(ruleId: String) {
        rulesLock.write {
            rules.removeIf { it.id == ruleId }
        }
    }
    
    /**
     * 关闭风险管理器
     */
    fun shutdown() {
        logger.info { "关闭风险管理器..." }
        
        // 关闭调度器
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        // 清理资源
        rules.clear()
        userRiskLimits.clear()
        instrumentRiskParams.clear()
        userOrderRates.clear()
        userPositions.clear()
        userPendingOrders.clear()
    }
}

/**
 * 滑动窗口速率限制器
 */
class SlidingWindowRateLimiter(
    private val windowSizeMillis: Long,
    private val maxEvents: Int
) {
    private val events = mutableListOf<Long>()
    private val lock = ReentrantReadWriteLock()
    private var lastUpdateTime = Instant.now()
    
    /**
     * 记录事件
     */
    fun recordEvent() {
        val currentTimeMillis = System.currentTimeMillis()
        
        lock.write {
            // 移除窗口外的事件
            val cutoff = currentTimeMillis - windowSizeMillis
            while (events.isNotEmpty() && events.first() < cutoff) {
                events.removeAt(0)
            }
            
            // 添加新事件
            events.add(currentTimeMillis)
            lastUpdateTime = Instant.now()
        }
    }
    
    /**
     * 获取当前速率
     */
    fun getCurrentRate(): Double {
        val currentTimeMillis = System.currentTimeMillis()
        
        return lock.read {
            // 移除窗口外的事件
            val cutoff = currentTimeMillis - windowSizeMillis
            val validEvents = events.filter { it >= cutoff }
            
            // 计算速率 (每秒事件数)
            validEvents.size.toDouble() / (windowSizeMillis / 1000.0)
        }
    }
    
    /**
     * 检查是否超过限制
     */
    fun isRateLimitExceeded(): Boolean {
        val currentTimeMillis = System.currentTimeMillis()
        
        return lock.read {
            // 移除窗口外的事件
            val cutoff = currentTimeMillis - windowSizeMillis
            val validEvents = events.filter { it >= cutoff }
            
            // 检查是否超过最大事件数
            validEvents.size >= maxEvents
        }
    }
    
    /**
     * 获取最后更新时间
     */
    fun getLastUpdateTime(): Instant = lastUpdateTime
} 