package com.hftdc.disruptorx.network

/**
 * Request message for network transport tests
 */
data class Request(
    val id: Long,
    val type: String,
    val data: Any
)

/**
 * Response message for network transport tests
 */
data class Response(
    val id: Long,
    val result: Any,
    val error: String? = null
)
