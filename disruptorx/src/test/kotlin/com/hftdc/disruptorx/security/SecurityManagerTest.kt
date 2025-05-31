package com.hftdc.disruptorx.security

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

class SecurityManagerTest {

    private lateinit var securityManager: SecurityManager
    private val nodeId = "test-node-1"

    @BeforeEach
    fun setup() {
        securityManager = SecurityManager(nodeId, 1.hours)
    }

    @Test
    fun `test create user`() = runBlocking {
        val user = securityManager.createUser("testuser", "password123", UserRole.TRADER)
        
        assertNotNull(user)
        assertEquals("testuser", user.username)
        assertEquals(UserRole.TRADER, user.role)
        assertTrue(user.isActive)
        assertTrue(user.createdTime > 0)
        assertNull(user.lastLoginTime)
        
        // 验证默认权限
        assertTrue(user.permissions.contains(Permission.READ_EVENTS))
        assertTrue(user.permissions.contains(Permission.WRITE_EVENTS))
        assertTrue(user.permissions.contains(Permission.VIEW_METRICS))
        assertFalse(user.permissions.contains(Permission.ADMIN_OPERATIONS))
    }

    @Test
    fun `test admin user has all permissions`() = runBlocking {
        val adminUser = securityManager.createUser("admin", "admin123", UserRole.ADMIN)
        
        assertEquals(UserRole.ADMIN, adminUser.role)
        assertEquals(Permission.values().toSet(), adminUser.permissions)
    }

    @Test
    fun `test viewer user has limited permissions`() = runBlocking {
        val viewerUser = securityManager.createUser("viewer", "viewer123", UserRole.VIEWER)
        
        assertEquals(UserRole.VIEWER, viewerUser.role)
        assertTrue(viewerUser.permissions.contains(Permission.READ_EVENTS))
        assertTrue(viewerUser.permissions.contains(Permission.VIEW_METRICS))
        assertFalse(viewerUser.permissions.contains(Permission.WRITE_EVENTS))
        assertFalse(viewerUser.permissions.contains(Permission.ADMIN_OPERATIONS))
    }

    @Test
    fun `test successful authentication`() = runBlocking {
        securityManager.createUser("testuser", "password123", UserRole.TRADER)
        
        val token = securityManager.authenticate("testuser", "password123")
        
        assertNotNull(token)
        assertEquals("testuser", token!!.username)
        assertEquals(UserRole.TRADER, token.role)
        assertEquals(nodeId, token.nodeId)
        assertTrue(token.issuedAt > 0)
        assertTrue(token.expiresAt > token.issuedAt)
        assertTrue(token.isValid())
        assertFalse(token.isExpired())
    }

    @Test
    fun `test failed authentication with wrong password`() = runBlocking {
        securityManager.createUser("testuser", "password123", UserRole.TRADER)
        
        val token = securityManager.authenticate("testuser", "wrongpassword")
        
        assertNull(token)
    }

    @Test
    fun `test failed authentication with non-existent user`() = runBlocking {
        val token = securityManager.authenticate("nonexistent", "password123")
        
        assertNull(token)
    }

    @Test
    fun `test token validation`() = runBlocking {
        securityManager.createUser("testuser", "password123", UserRole.TRADER)
        val token = securityManager.authenticate("testuser", "password123")!!
        
        val validatedToken = securityManager.validateToken(token.tokenId)
        
        assertNotNull(validatedToken)
        assertEquals(token.tokenId, validatedToken!!.tokenId)
        assertEquals(token.userId, validatedToken.userId)
    }

    @Test
    fun `test invalid token validation`() {
        val validatedToken = securityManager.validateToken("invalid-token-id")
        
        assertNull(validatedToken)
    }

    @Test
    fun `test logout`() = runBlocking {
        securityManager.createUser("testuser", "password123", UserRole.TRADER)
        val token = securityManager.authenticate("testuser", "password123")!!
        
        // 验证令牌有效
        assertNotNull(securityManager.validateToken(token.tokenId))
        
        // 注销
        val logoutResult = securityManager.logout(token.tokenId)
        assertTrue(logoutResult)
        
        // 验证令牌无效
        assertNull(securityManager.validateToken(token.tokenId))
    }

    @Test
    fun `test logout with invalid token`() = runBlocking {
        val logoutResult = securityManager.logout("invalid-token-id")
        assertFalse(logoutResult)
    }

    @Test
    fun `test permission checking with token`() = runBlocking {
        securityManager.createUser("trader", "password123", UserRole.TRADER)
        val token = securityManager.authenticate("trader", "password123")!!
        
        assertTrue(securityManager.hasPermission(token, Permission.READ_EVENTS))
        assertTrue(securityManager.hasPermission(token, Permission.WRITE_EVENTS))
        assertTrue(securityManager.hasPermission(token, Permission.VIEW_METRICS))
        assertFalse(securityManager.hasPermission(token, Permission.ADMIN_OPERATIONS))
    }

    @Test
    fun `test permission checking with token id`() = runBlocking {
        securityManager.createUser("trader", "password123", UserRole.TRADER)
        val token = securityManager.authenticate("trader", "password123")!!
        
        assertTrue(securityManager.hasPermission(token.tokenId, Permission.READ_EVENTS))
        assertTrue(securityManager.hasPermission(token.tokenId, Permission.WRITE_EVENTS))
        assertFalse(securityManager.hasPermission(token.tokenId, Permission.ADMIN_OPERATIONS))
        
        // 无效令牌ID
        assertFalse(securityManager.hasPermission("invalid-token", Permission.READ_EVENTS))
    }

    @Test
    fun `test data encryption and decryption`() {
        val originalData = "This is sensitive data that needs to be encrypted"
        
        val encryptedData = securityManager.encrypt(originalData)
        assertNotEquals(originalData, encryptedData)
        assertTrue(encryptedData.isNotEmpty())
        
        val decryptedData = securityManager.decrypt(encryptedData)
        assertEquals(originalData, decryptedData)
    }

    @Test
    fun `test encryption produces different results for same input`() {
        val data = "test data"
        
        val encrypted1 = securityManager.encrypt(data)
        val encrypted2 = securityManager.encrypt(data)
        
        // 由于使用了随机IV，相同输入应该产生不同的加密结果
        assertNotEquals(encrypted1, encrypted2)
        
        // 但解密结果应该相同
        assertEquals(data, securityManager.decrypt(encrypted1))
        assertEquals(data, securityManager.decrypt(encrypted2))
    }

    @Test
    fun `test audit log creation`() = runBlocking {
        securityManager.createUser("testuser", "password123", UserRole.TRADER)
        securityManager.authenticate("testuser", "password123")
        
        val auditLogs = securityManager.getAuditLogs()
        
        assertTrue(auditLogs.isNotEmpty())
        
        // 应该有创建用户和登录的日志
        val createUserLog = auditLogs.find { it.action == "CREATE_USER" }
        assertNotNull(createUserLog)
        assertEquals("user:testuser", createUserLog!!.resource)
        assertEquals("SUCCESS", createUserLog.result)
        
        val loginLog = auditLogs.find { it.action == "LOGIN" }
        assertNotNull(loginLog)
        assertEquals("user:testuser", loginLog!!.resource)
        assertEquals("SUCCESS", loginLog.result)
    }

    @Test
    fun `test audit log filtering`() = runBlocking {
        val user = securityManager.createUser("testuser", "password123", UserRole.TRADER)
        securityManager.authenticate("testuser", "password123")
        
        // 按用户ID过滤
        val userLogs = securityManager.getAuditLogs(userId = user.userId)
        assertTrue(userLogs.isNotEmpty())
        assertTrue(userLogs.all { it.userId == user.userId })
        
        // 按操作过滤
        val loginLogs = securityManager.getAuditLogs(action = "LOGIN")
        assertTrue(loginLogs.isNotEmpty())
        assertTrue(loginLogs.all { it.action == "LOGIN" })
        
        // 组合过滤
        val userLoginLogs = securityManager.getAuditLogs(userId = user.userId, action = "LOGIN")
        assertTrue(userLoginLogs.isNotEmpty())
        assertTrue(userLoginLogs.all { it.userId == user.userId && it.action == "LOGIN" })
    }

    @Test
    fun `test cleanup expired tokens`() = runBlocking {
        // 创建一个短期有效的安全管理器
        val shortTermSecurityManager = SecurityManager(nodeId, kotlin.time.Duration.parse("1ms"))

        shortTermSecurityManager.createUser("testuser", "password123", UserRole.TRADER)
        val token = shortTermSecurityManager.authenticate("testuser", "password123")!!

        // 等待令牌过期
        kotlinx.coroutines.delay(10)

        // 令牌应该过期
        assertTrue(token.isExpired())

        // 清理过期令牌
        shortTermSecurityManager.cleanupExpiredTokens()

        // 验证令牌被清理
        assertNull(shortTermSecurityManager.validateToken(token.tokenId))

        // 验证审计日志
        val auditLogs = shortTermSecurityManager.getAuditLogs(action = "TOKEN_EXPIRED")
        assertTrue(auditLogs.isNotEmpty())
    }

    @Test
    fun `test default admin user exists`() = runBlocking {
        // 默认管理员应该能够登录
        val adminToken = securityManager.authenticate("admin", "admin123")
        
        assertNotNull(adminToken)
        assertEquals("admin", adminToken!!.username)
        assertEquals(UserRole.ADMIN, adminToken.role)
        assertEquals(Permission.values().toSet(), adminToken.permissions)
    }

    @Test
    fun `test system user permissions`() = runBlocking {
        val systemUser = securityManager.createUser("system", "system123", UserRole.SYSTEM)
        
        assertEquals(UserRole.SYSTEM, systemUser.role)
        assertTrue(systemUser.permissions.contains(Permission.READ_EVENTS))
        assertTrue(systemUser.permissions.contains(Permission.WRITE_EVENTS))
        assertTrue(systemUser.permissions.contains(Permission.MANAGE_NODES))
        assertFalse(systemUser.permissions.contains(Permission.ADMIN_OPERATIONS))
    }

    @Test
    fun `test failed login attempts are logged`() = runBlocking {
        securityManager.createUser("testuser", "password123", UserRole.TRADER)
        
        // 尝试错误密码登录
        securityManager.authenticate("testuser", "wrongpassword")
        
        // 尝试不存在的用户登录
        securityManager.authenticate("nonexistent", "password")
        
        val auditLogs = securityManager.getAuditLogs(action = "LOGIN_ATTEMPT")
        assertEquals(2, auditLogs.size)
        
        val failedLogs = auditLogs.filter { it.result == "FAILED" }
        assertEquals(2, failedLogs.size)
        
        val invalidPasswordLog = failedLogs.find { it.details["reason"] == "INVALID_PASSWORD" }
        assertNotNull(invalidPasswordLog)
        
        val userNotFoundLog = failedLogs.find { it.details["reason"] == "USER_NOT_FOUND" }
        assertNotNull(userNotFoundLog)
    }
}
