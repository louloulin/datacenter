package com.hftdc.journal

import com.hftdc.model.OrderBookSnapshot
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存中的日志服务实现，用于测试
 */
class InMemoryJournalService : JournalService {
    private val events = mutableListOf<JournalEvent>()
    private val snapshots = ConcurrentHashMap<String, MutableList<JournalSnapshot>>()
    private val eventIdCounter = AtomicLong(0)
    
    override fun journal(event: JournalEvent) {
        events.add(event)
    }
    
    override fun saveSnapshot(instrumentId: String, snapshot: OrderBookSnapshot): String {
        val snapshotId = "snapshot-${System.nanoTime()}"
        val journalSnapshot = JournalSnapshot(
            id = snapshotId,
            timestamp = Instant.now().toEpochMilli(),
            instrumentId = instrumentId,
            snapshot = snapshot
        )
        
        snapshots.computeIfAbsent(instrumentId) { mutableListOf() }.add(journalSnapshot)
        return snapshotId
    }
    
    override fun getLatestSnapshot(instrumentId: String): JournalSnapshot? {
        return snapshots[instrumentId]?.maxByOrNull { it.timestamp }
    }
    
    override fun getEventsSince(instrumentId: String, timestamp: Long): List<JournalEvent> {
        return events.filter { 
            it.timestamp >= timestamp && 
            (it is OrderSubmittedEvent && it.order.instrumentId == instrumentId ||
             it is OrderCanceledEvent && it.instrumentId == instrumentId ||
             it is TradeCreatedEvent && it.trade.instrumentId == instrumentId)
        }
    }
    
    override fun getEventsBetween(instrumentId: String, fromTimestamp: Long, toTimestamp: Long): List<JournalEvent> {
        return events.filter { 
            it.timestamp in fromTimestamp..toTimestamp && 
            (it is OrderSubmittedEvent && it.order.instrumentId == instrumentId ||
             it is OrderCanceledEvent && it.instrumentId == instrumentId ||
             it is TradeCreatedEvent && it.trade.instrumentId == instrumentId)
        }
    }
    
    override fun getInstrumentsWithSnapshots(): Set<String> {
        return snapshots.keys
    }
    
    override fun shutdown() {
        events.clear()
        snapshots.clear()
    }
} 