package com.hftdc.disruptorx

/**
 * BenchmarkMessage for tests
 */
data class BenchmarkMessage(
    val payload: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as BenchmarkMessage
        
        if (!payload.contentEquals(other.payload)) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        return payload.contentHashCode()
    }
}
