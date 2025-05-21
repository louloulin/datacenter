package com.hftdc.journal

import com.hftdc.model.OrderBookSnapshot

/**
 * 日志服务接口 - 用于记录和查询交易事件和快照
 */
interface JournalService {
    /**
     * 记录事件
     */
    fun journal(event: JournalEvent)
    
    /**
     * 保存快照并返回快照ID
     */
    fun saveSnapshot(instrumentId: String, snapshot: OrderBookSnapshot): String
    
    /**
     * 获取指定品种的最新快照
     */
    fun getLatestSnapshot(instrumentId: String): JournalSnapshot?
    
    /**
     * 获取指定时间戳之后的所有事件
     */
    fun getEventsSince(instrumentId: String, timestamp: Long): List<JournalEvent>
    
    /**
     * 获取指定时间范围内的所有事件
     */
    fun getEventsBetween(instrumentId: String, fromTimestamp: Long, toTimestamp: Long): List<JournalEvent>
    
    /**
     * 获取所有有快照的交易品种ID
     */
    fun getInstrumentsWithSnapshots(): Set<String>
    
    /**
     * 关闭日志服务
     */
    fun shutdown() {
        // 默认实现为空，由具体实现类覆盖
    }
} 