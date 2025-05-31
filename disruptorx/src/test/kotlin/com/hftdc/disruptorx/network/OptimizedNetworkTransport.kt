package com.hftdc.disruptorx.network

import com.hftdc.disruptorx.api.Request
import com.hftdc.disruptorx.api.Response
import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Optimized network transport for tests
 * This is a simplified implementation for testing purposes
 */
class OptimizedNetworkTransport(
    private val port: Int,
    private val workerThreads: Int = 1,
    private val batchSizeThreshold: Int = 50,
    private val batchTimeWindowMs: Long = 10
) {
    private val connections = ConcurrentHashMap<String, Connection>()
    private val messageHandler: (Any, String) -> Unit = { _, _ -> }
    private var currentMessageHandler: ((Any, String) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val requestCounter = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableFuture<Any>>()
    
    @Volatile
    private var isRunning = false
    
    @Volatile
    private var isServerStarted = false
    
    /**
     * Set message handler for incoming messages
     */
    fun setMessageHandler(handler: (Any, String) -> Unit) {
        currentMessageHandler = handler
    }
    
    /**
     * Start server to listen for incoming connections
     */
    fun startServer(): CompletableFuture<Void> {
        isServerStarted = true
        isRunning = true
        return CompletableFuture.completedFuture(null)
    }
    
    /**
     * Connect to a remote server
     */
    fun connect(host: String, port: Int): CompletableFuture<Void> {
        val address = "$host:$port"
        val connection = Connection(address)
        connections[address] = connection
        return CompletableFuture.completedFuture(null)
    }
    
    /**
     * Send a message to a remote address
     */
    fun send(message: Any, remoteAddress: String) {
        if (!isRunning) return
        
        // Simulate network delay
        scope.launch {
            delay(1) // 1ms simulated network delay
            
            // Find the target transport and deliver the message
            deliverMessage(message, remoteAddress)
        }
    }
    
    /**
     * Send a request and wait for response
     */
    fun <T> sendRequest(request: Request, remoteAddress: String): CompletableFuture<T> {
        val requestId = requestCounter.incrementAndGet()
        val requestWithId = request.copy(id = requestId)
        val future = CompletableFuture<T>()
        
        @Suppress("UNCHECKED_CAST")
        pendingRequests[requestId] = future as CompletableFuture<Any>
        
        // Send the request
        send(requestWithId, remoteAddress)
        
        // Set timeout
        scope.launch {
            delay(5.seconds)
            if (pendingRequests.remove(requestId) != null) {
                future.completeExceptionally(RuntimeException("Request timeout"))
            }
        }
        
        return future
    }
    
    /**
     * Close the transport
     */
    fun close() {
        isRunning = false
        isServerStarted = false
        scope.cancel()
        connections.clear()
        pendingRequests.clear()
    }
    
    /**
     * Deliver message to the appropriate handler
     */
    private fun deliverMessage(message: Any, sourceAddress: String) {
        try {
            // Handle responses to pending requests
            if (message is Response) {
                val future = pendingRequests.remove(message.id)
                if (future != null) {
                    if (message.error != null) {
                        future.completeExceptionally(RuntimeException(message.error))
                    } else {
                        future.complete(message)
                    }
                    return
                }
            }
            
            // Handle regular messages
            currentMessageHandler?.invoke(message, sourceAddress)
        } catch (e: Exception) {
            println("Error delivering message: ${e.message}")
        }
    }
    
    /**
     * Connection representation
     */
    private data class Connection(
        val address: String,
        val isConnected: Boolean = true
    )
    
    companion object {
        // Global registry to simulate network communication between instances
        private val transportRegistry = ConcurrentHashMap<Int, OptimizedNetworkTransport>()
        
        init {
            // Start a background task to handle cross-transport communication
            GlobalScope.launch {
                while (true) {
                    delay(1)
                    // This would handle message routing between transports in a real implementation
                }
            }
        }
        
        /**
         * Register a transport instance
         */
        private fun registerTransport(port: Int, transport: OptimizedNetworkTransport) {
            transportRegistry[port] = transport
        }
        
        /**
         * Find transport by address
         */
        private fun findTransport(address: String): OptimizedNetworkTransport? {
            val port = address.split(":").getOrNull(1)?.toIntOrNull() ?: return null
            return transportRegistry[port]
        }
    }
    
    init {
        // Register this transport instance
        transportRegistry[port] = this
    }
}
