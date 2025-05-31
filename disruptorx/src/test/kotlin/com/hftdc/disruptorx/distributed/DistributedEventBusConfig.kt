package com.hftdc.disruptorx.distributed

/**
 * Network configuration for distributed event bus
 */
data class NetworkConfig(
    val port: Int = 9090,
    val connectionTimeout: Long = 5000,
    val eventBatchSize: Int = 100,
    val eventBatchTimeWindowMs: Long = 10,
    val maxConnections: Int = 1000,
    val workerThreads: Int = 4
)

/**
 * Distributed event bus configuration
 */
data class DistributedEventBusConfig(
    val networkConfig: NetworkConfig = NetworkConfig(),
    val replicationFactor: Int = 3,
    val enableCompression: Boolean = true,
    val enableEncryption: Boolean = false,
    val heartbeatIntervalMs: Long = 1000,
    val nodeTimeoutMs: Long = 5000
)
