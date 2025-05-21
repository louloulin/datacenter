package com.hftdc.risk

import com.hftdc.model.Order
import com.hftdc.model.OrderSide
import java.time.Instant

/**
 * 账户状态检查规则 - 检查用户账户是否允许交易
 */
class AccountStatusRule : RiskRule {
    override val id: String = "account-status"
    override val name: String = "账户状态检查"
    override val priority: Int = 1 // 最高优先级
    
    override fun check(context: RiskContext, order: Order): RiskCheckResult {
        // 检查用户风险级别
        val riskLevel = context.getRiskLevel()
        
        // 如果账户被冻结，拒绝交易
        if (riskLevel == RiskLevel.FROZEN) {
            return RiskCheckResult(
                passed = false,
                reason = "账户已被冻结，禁止交易",
                timestamp = Instant.now().toEpochMilli()
            )
        }
        
        return RiskCheckResult(passed = true)
    }
}

/**
 * 余额检查规则 - 检查用户是否有足够的余额进行交易
 */
class BalanceCheckRule : RiskRule {
    override val id: String = "balance-check"
    override val name: String = "余额检查"
    override val priority: Int = 2
    
    override fun check(context: RiskContext, order: Order): RiskCheckResult {
        // 对于卖单，如果是现货交易，检查用户是否有足够的资产
        if (order.side == OrderSide.SELL) {
            val position = context.getPosition(order.instrumentId)
            if (position < order.quantity) {
                return RiskCheckResult(
                    passed = false,
                    reason = "持仓不足，当前持仓: $position, 需要: ${order.quantity}",
                    timestamp = Instant.now().toEpochMilli()
                )
            }
        } else {
            // 对于买单，检查是否有足够的资金
            // 这里简化实现，假设使用USDT作为基础货币
            val requiredAmount = (order.price ?: 0) * order.quantity
            val availableBalance = context.getAvailableBalance("USDT")
            
            if (requiredAmount > availableBalance) {
                return RiskCheckResult(
                    passed = false,
                    reason = "余额不足，当前余额: $availableBalance, 需要: $requiredAmount",
                    timestamp = Instant.now().toEpochMilli()
                )
            }
        }
        
        return RiskCheckResult(passed = true)
    }
}

/**
 * 订单大小检查规则 - 检查订单大小是否在允许的范围内
 */
class OrderSizeRule : RiskRule {
    override val id: String = "order-size"
    override val name: String = "订单大小检查"
    override val priority: Int = 3
    
    override fun check(context: RiskContext, order: Order): RiskCheckResult {
        val params = context.getInstrumentRiskParams(order.instrumentId)
        
        // 检查最小订单大小
        if (order.quantity < params.minOrderSize) {
            return RiskCheckResult(
                passed = false,
                reason = "订单数量小于最小限制, 当前: ${order.quantity}, 最小: ${params.minOrderSize}",
                timestamp = Instant.now().toEpochMilli()
            )
        }
        
        // 检查最大订单大小
        if (order.quantity > params.maxOrderSize) {
            return RiskCheckResult(
                passed = false,
                reason = "订单数量超过最大限制, 当前: ${order.quantity}, 最大: ${params.maxOrderSize}",
                timestamp = Instant.now().toEpochMilli()
            )
        }
        
        // 检查订单金额是否超过用户限制
        val orderAmount = (order.price ?: 0) * order.quantity
        val userLimits = context.getRiskLevel().let {
            when (it) {
                RiskLevel.LOW -> 100_000_000_000L // 1亿
                RiskLevel.MEDIUM -> 10_000_000_000L // 1千万
                RiskLevel.HIGH -> 1_000_000_000L // 1百万
                RiskLevel.FROZEN -> 0L
            }
        }
        
        if (orderAmount > userLimits) {
            return RiskCheckResult(
                passed = false,
                reason = "订单金额超过用户限制, 当前: $orderAmount, 限制: $userLimits",
                timestamp = Instant.now().toEpochMilli()
            )
        }
        
        return RiskCheckResult(passed = true)
    }
}

/**
 * 持仓限制规则 - 检查用户持仓是否超过限制
 */
class PositionLimitRule : RiskRule {
    override val id: String = "position-limit"
    override val name: String = "持仓限制检查"
    override val priority: Int = 4
    
    override fun check(context: RiskContext, order: Order): RiskCheckResult {
        val instrumentRiskParams = context.getInstrumentRiskParams(order.instrumentId)
        
        // 计算潜在的新持仓
        val currentPosition = context.getPosition(order.instrumentId)
        val newPosition = if (order.side == OrderSide.BUY) {
            currentPosition + order.quantity
        } else {
            currentPosition - order.quantity
        }
        
        // 检查是否允许做空
        if (!instrumentRiskParams.allowShort && newPosition < 0) {
            return RiskCheckResult(
                passed = false,
                reason = "不允许做空该品种",
                timestamp = Instant.now().toEpochMilli()
            )
        }
        
        // 检查是否超过最大持仓限制
        val absNewPosition = kotlin.math.abs(newPosition)
        if (absNewPosition > instrumentRiskParams.maxPositionSize) {
            return RiskCheckResult(
                passed = false,
                reason = "持仓超过限制, 当前: $currentPosition, 新持仓将为: $newPosition, 限制: ${instrumentRiskParams.maxPositionSize}",
                timestamp = Instant.now().toEpochMilli()
            )
        }
        
        return RiskCheckResult(passed = true)
    }
}

/**
 * 速率限制规则 - 检查用户下单频率是否超过限制
 */
class RateLimitRule : RiskRule {
    override val id: String = "rate-limit"
    override val name: String = "速率限制检查"
    override val priority: Int = 5
    
    override fun check(context: RiskContext, order: Order): RiskCheckResult {
        // 获取品种的速率限制
        val instrumentRiskParams = context.getInstrumentRiskParams(order.instrumentId)
        val maxOrdersPerSecond = instrumentRiskParams.maxOrdersPerSecond
        
        // 获取用户当前的下单速率
        val currentRate = context.getOrderRate()
        
        // 根据用户风险级别调整限制
        val adjustedLimit = when (context.getRiskLevel()) {
            RiskLevel.LOW -> maxOrdersPerSecond.toDouble()
            RiskLevel.MEDIUM -> maxOrdersPerSecond * 0.75
            RiskLevel.HIGH -> maxOrdersPerSecond * 0.5
            RiskLevel.FROZEN -> 0.0
        }
        
        // 检查是否超过速率限制
        if (currentRate >= adjustedLimit) {
            return RiskCheckResult(
                passed = false,
                reason = "下单频率超过限制, 当前: $currentRate/秒, 限制: $adjustedLimit/秒",
                timestamp = Instant.now().toEpochMilli()
            )
        }
        
        return RiskCheckResult(passed = true)
    }
} 