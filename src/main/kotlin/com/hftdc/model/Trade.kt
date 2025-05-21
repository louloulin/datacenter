package com.hftdc.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 交易实体 - 表示订单成交信息
 */
@Serializable
data class Trade(
    val id: Long,                 // 交易ID
    val buyOrderId: Long,         // 买单ID
    val sellOrderId: Long,        // 卖单ID
    val buyUserId: Long,          // 买方用户ID
    val sellUserId: Long,         // 卖方用户ID
    val instrumentId: String,     // 交易品种ID
    val price: Long,              // 成交价格
    val quantity: Long,           // 成交数量
    val timestamp: Long = Instant.now().toEpochMilli(), // 成交时间戳
    val makerOrderId: Long,       // 挂单方订单ID (提供流动性)
    val takerOrderId: Long,       // 吃单方订单ID (消耗流动性)
    val buyFee: Long,             // 买方费用
    val sellFee: Long             // 卖方费用
) : SerializableMessage {
    
    /**
     * 计算交易金额
     */
    fun amount(): Long = price * quantity
    
    /**
     * 计算总费用
     */
    fun totalFee(): Long = buyFee + sellFee
    
    /**
     * 检查用户是否是买方
     */
    fun isBuyer(userId: Long): Boolean = buyUserId == userId
    
    /**
     * 检查用户是否是卖方
     */
    fun isSeller(userId: Long): Boolean = sellUserId == userId
    
    /**
     * 检查用户是否是市场制造者
     */
    fun isMaker(userId: Long): Boolean {
        val isBuyMaker = buyOrderId == makerOrderId && buyUserId == userId
        val isSellMaker = sellOrderId == makerOrderId && sellUserId == userId
        return isBuyMaker || isSellMaker
    }
    
    /**
     * 检查用户是否是吃单方
     */
    fun isTaker(userId: Long): Boolean {
        val isBuyTaker = buyOrderId == takerOrderId && buyUserId == userId
        val isSellTaker = sellOrderId == takerOrderId && sellUserId == userId
        return isBuyTaker || isSellTaker
    }
    
    /**
     * 获取用户在此交易中的费用
     */
    fun getFeeForUser(userId: Long): Long {
        return when {
            buyUserId == userId -> buyFee
            sellUserId == userId -> sellFee
            else -> 0
        }
    }
} 