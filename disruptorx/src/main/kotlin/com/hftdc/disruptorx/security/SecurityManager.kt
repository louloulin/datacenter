package com.hftdc.disruptorx.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * 用户角色枚举
 */
enum class UserRole {
    ADMIN,      // 管理员
    TRADER,     // 交易员
    VIEWER,     // 只读用户
    SYSTEM      // 系统用户
}

/**
 * 权限枚举
 */
enum class Permission {
    READ_EVENTS,        // 读取事件
    WRITE_EVENTS,       // 写入事件
    MANAGE_WORKFLOWS,   // 管理工作流
    MANAGE_NODES,       // 管理节点
    VIEW_METRICS,       // 查看指标
    ADMIN_OPERATIONS    // 管理操作
}

/**
 * 用户信息
 */
data class UserInfo(
    val userId: String,
    val username: String,
    val passwordHash: String,
    val salt: String,
    val role: UserRole,
    val permissions: Set<Permission>,
    val createdTime: Long = System.currentTimeMillis(),
    val lastLoginTime: Long? = null,
    val isActive: Boolean = true
)

/**
 * 认证令牌
 */
data class AuthToken(
    val tokenId: String,
    val userId: String,
    val username: String,
    val role: UserRole,
    val permissions: Set<Permission>,
    val issuedAt: Long,
    val expiresAt: Long,
    val nodeId: String
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
    fun isValid(): Boolean = !isExpired()
}

/**
 * 审计日志条目
 */
data class AuditLogEntry(
    val timestamp: Long,
    val userId: String,
    val action: String,
    val resource: String,
    val result: String,
    val details: Map<String, String> = emptyMap(),
    val nodeId: String
)

/**
 * 安全管理器
 */
class SecurityManager(
    private val nodeId: String,
    private val tokenValidityDuration: Duration = 8.hours
) {
    private val users = ConcurrentHashMap<String, UserInfo>()
    private val activeTokens = ConcurrentHashMap<String, AuthToken>()
    private val auditLogs = mutableListOf<AuditLogEntry>()
    private val auditMutex = Mutex()
    
    private val secureRandom = SecureRandom()
    private val encryptionKey: SecretKey
    
    init {
        // 生成加密密钥
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256)
        encryptionKey = keyGenerator.generateKey()
        
        // 创建默认管理员用户
        createDefaultAdminUser()
    }
    
    /**
     * 创建用户
     */
    suspend fun createUser(
        username: String,
        password: String,
        role: UserRole
    ): UserInfo {
        val userId = generateUserId()
        val salt = generateSalt()
        val passwordHash = hashPassword(password, salt)
        val permissions = getDefaultPermissions(role)
        
        val user = UserInfo(
            userId = userId,
            username = username,
            passwordHash = passwordHash,
            salt = salt,
            role = role,
            permissions = permissions
        )
        
        users[userId] = user
        
        auditLog(
            userId = "system",
            action = "CREATE_USER",
            resource = "user:$username",
            result = "SUCCESS",
            details = mapOf("role" to role.name)
        )
        
        return user
    }
    
    /**
     * 用户认证
     */
    suspend fun authenticate(username: String, password: String): AuthToken? {
        val user = users.values.find { it.username == username && it.isActive }
        
        if (user == null) {
            auditLog(
                userId = "anonymous",
                action = "LOGIN_ATTEMPT",
                resource = "user:$username",
                result = "FAILED",
                details = mapOf("reason" to "USER_NOT_FOUND")
            )
            return null
        }
        
        val passwordHash = hashPassword(password, user.salt)
        if (passwordHash != user.passwordHash) {
            auditLog(
                userId = user.userId,
                action = "LOGIN_ATTEMPT",
                resource = "user:$username",
                result = "FAILED",
                details = mapOf("reason" to "INVALID_PASSWORD")
            )
            return null
        }
        
        // 创建认证令牌
        val token = createAuthToken(user)
        activeTokens[token.tokenId] = token
        
        // 更新最后登录时间
        users[user.userId] = user.copy(lastLoginTime = System.currentTimeMillis())
        
        auditLog(
            userId = user.userId,
            action = "LOGIN",
            resource = "user:$username",
            result = "SUCCESS",
            details = mapOf("token_id" to token.tokenId)
        )
        
        return token
    }
    
    /**
     * 验证令牌
     */
    fun validateToken(tokenId: String): AuthToken? {
        val token = activeTokens[tokenId]
        return if (token?.isValid() == true) token else null
    }
    
    /**
     * 注销
     */
    suspend fun logout(tokenId: String): Boolean {
        val token = activeTokens.remove(tokenId)
        
        if (token != null) {
            auditLog(
                userId = token.userId,
                action = "LOGOUT",
                resource = "token:$tokenId",
                result = "SUCCESS"
            )
            return true
        }
        
        return false
    }
    
    /**
     * 检查权限
     */
    fun hasPermission(token: AuthToken, permission: Permission): Boolean {
        return token.isValid() && permission in token.permissions
    }
    
    /**
     * 检查权限（通过令牌ID）
     */
    fun hasPermission(tokenId: String, permission: Permission): Boolean {
        val token = validateToken(tokenId) ?: return false
        return hasPermission(token, permission)
    }
    
    /**
     * 加密数据
     */
    fun encrypt(data: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16)
        secureRandom.nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, ivSpec)
        val encryptedData = cipher.doFinal(data.toByteArray())
        
        // 将IV和加密数据组合
        val combined = iv + encryptedData
        return java.util.Base64.getEncoder().encodeToString(combined)
    }
    
    /**
     * 解密数据
     */
    fun decrypt(encryptedData: String): String {
        val combined = java.util.Base64.getDecoder().decode(encryptedData)
        val iv = combined.sliceArray(0..15)
        val encrypted = combined.sliceArray(16 until combined.size)
        
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, ivSpec)
        
        val decryptedData = cipher.doFinal(encrypted)
        return String(decryptedData)
    }
    
    /**
     * 获取审计日志
     */
    suspend fun getAuditLogs(
        userId: String? = null,
        action: String? = null,
        limit: Int = 100
    ): List<AuditLogEntry> {
        auditMutex.withLock {
            return auditLogs
                .filter { entry ->
                    (userId == null || entry.userId == userId) &&
                    (action == null || entry.action == action)
                }
                .takeLast(limit)
        }
    }
    
    /**
     * 清理过期令牌
     */
    suspend fun cleanupExpiredTokens() {
        val expiredTokens = activeTokens.values.filter { it.isExpired() }
        expiredTokens.forEach { token ->
            activeTokens.remove(token.tokenId)
            auditLog(
                userId = token.userId,
                action = "TOKEN_EXPIRED",
                resource = "token:${token.tokenId}",
                result = "CLEANUP"
            )
        }
    }
    
    private fun createDefaultAdminUser() {
        val adminId = "admin-001"
        val salt = generateSalt()
        val passwordHash = hashPassword("admin123", salt) // 默认密码，生产环境应该修改
        
        val adminUser = UserInfo(
            userId = adminId,
            username = "admin",
            passwordHash = passwordHash,
            salt = salt,
            role = UserRole.ADMIN,
            permissions = Permission.values().toSet()
        )
        
        users[adminId] = adminUser
    }
    
    private fun createAuthToken(user: UserInfo): AuthToken {
        val tokenId = generateTokenId()
        val now = System.currentTimeMillis()
        
        return AuthToken(
            tokenId = tokenId,
            userId = user.userId,
            username = user.username,
            role = user.role,
            permissions = user.permissions,
            issuedAt = now,
            expiresAt = now + tokenValidityDuration.inWholeMilliseconds,
            nodeId = nodeId
        )
    }
    
    private fun getDefaultPermissions(role: UserRole): Set<Permission> {
        return when (role) {
            UserRole.ADMIN -> Permission.values().toSet()
            UserRole.TRADER -> setOf(
                Permission.READ_EVENTS,
                Permission.WRITE_EVENTS,
                Permission.VIEW_METRICS
            )
            UserRole.VIEWER -> setOf(
                Permission.READ_EVENTS,
                Permission.VIEW_METRICS
            )
            UserRole.SYSTEM -> setOf(
                Permission.READ_EVENTS,
                Permission.WRITE_EVENTS,
                Permission.MANAGE_NODES
            )
        }
    }
    
    private suspend fun auditLog(
        userId: String,
        action: String,
        resource: String,
        result: String,
        details: Map<String, String> = emptyMap()
    ) {
        val entry = AuditLogEntry(
            timestamp = System.currentTimeMillis(),
            userId = userId,
            action = action,
            resource = resource,
            result = result,
            details = details,
            nodeId = nodeId
        )
        
        auditMutex.withLock {
            auditLogs.add(entry)
            // 保持最近1000条日志
            if (auditLogs.size > 1000) {
                auditLogs.removeAt(0)
            }
        }
    }
    
    private fun hashPassword(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val saltedPassword = password + salt
        val hash = digest.digest(saltedPassword.toByteArray())
        return java.util.Base64.getEncoder().encodeToString(hash)
    }
    
    private fun generateSalt(): String {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        return java.util.Base64.getEncoder().encodeToString(salt)
    }
    
    private fun generateUserId(): String {
        return "user-${System.currentTimeMillis()}-${secureRandom.nextInt(10000)}"
    }
    
    private fun generateTokenId(): String {
        return "token-${System.currentTimeMillis()}-${secureRandom.nextInt(10000)}"
    }
}
