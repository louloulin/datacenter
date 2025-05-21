package com.hftdc.risk

import com.hftdc.model.Order
import com.hftdc.model.OrderSide
import com.hftdc.model.SerializableMessage
import kotlinx.serialization.Serializable

/**
 * 风险检查结果
 */
@Serializable
data class RiskCheckResult(
    val passed: Boolean,
    val reason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) : SerializableMessage

/**
 * 风险规则接口
 */
interface RiskRule {
    /**
     * 规则ID
     */
    val id: String
    
    /**
     * 规则名称
     */
    val name: String
    
    /**
     * 规则优先级 - 数字越小优先级越高
     */
    val priority: Int
    
    /**
     * 执行风险检查
     */
    fun check(context: RiskContext, order: Order): RiskCheckResult
}

/**
 * 风险检查上下文
 */
data class RiskContext(
    /**
     * 用户ID
     */
    val userId: Long,
    
    /**
     * 账户ID列表
     */
    val accountIds: List<Long>,
    
    /**
     * 获取用户可用余额
     */
    val getAvailableBalance: (String) -> Long,
    
    /**
     * 获取用户持仓
     */
    val getPosition: (String) -> Long,
    
    /**
     * 获取用户未成交订单总数量
     */
    val getPendingOrdersCount: (String, OrderSide) -> Long,
    
    /**
     * 获取用户订单频率（每秒）
     */
    val getOrderRate: () -> Double,
    
    /**
     * 获取用户的风险级别
     */
    val getRiskLevel: () -> RiskLevel,
    
    /**
     * 获取品种的风险参数
     */
    val getInstrumentRiskParams: (String) -> InstrumentRiskParams
)

/**
 * 用户风险级别
 */
enum class RiskLevel {
    LOW,        // 低风险 - 无特殊限制
    MEDIUM,     // 中风险 - 有一定限制
    HIGH,       // 高风险 - 严格限制
    FROZEN      // 冻结 - 禁止交易
}

/**
 * 品种风险参数
 */
@Serializable
data class InstrumentRiskParams(
    /**
     * 品种ID
     */
    val instrumentId: String,
    
    /**
     * 最大订单数量
     */
    val maxOrderSize: Long,
    
    /**
     * 最小订单数量
     */
    val minOrderSize: Long,
    
    /**
     * 价格波动限制（百分比）
     */
    val priceFluctLimit: Double,
    
    /**
     * 保证金要求（百分比）
     */
    val marginRequirement: Double,
    
    /**
     * 最大杠杆倍数
     */
    val maxLeverage: Int,
    
    /**
     * 是否允许做空
     */
    val allowShort: Boolean,
    
    /**
     * 每个账户最大持仓量
     */
    val maxPositionSize: Long,
    
    /**
     * 每秒最大订单数
     */
    val maxOrdersPerSecond: Int
) : SerializableMessage

/**
 * 用户风险限制
 */
@Serializable
data class UserRiskLimits(
    /**
     * 用户ID
     */
    val userId: Long,
    
    /**
     * 单笔订单最大金额
     */
    val maxOrderAmount: Long,
    
    /**
     * 每日最大交易金额
     */
    val maxDailyAmount: Long,
    
    /**
     * 每个品种最大持仓数量
     */
    val maxPositionPerInstrument: Long,
    
    /**
     * 最大未成交订单数
     */
    val maxPendingOrders: Int,
    
    /**
     * 每秒最大订单数
     */
    val maxOrdersPerSecond: Int,
    
    /**
     * 风险级别
     */
    val riskLevel: RiskLevel
) : SerializableMessage 