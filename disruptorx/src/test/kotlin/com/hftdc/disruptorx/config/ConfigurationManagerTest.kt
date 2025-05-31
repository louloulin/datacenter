package com.hftdc.disruptorx.config

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class ConfigurationManagerTest {

    private lateinit var configManager: ConfigurationManager
    private lateinit var testConfigFile: File
    
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        testConfigFile = tempDir.resolve("test-config.json").toFile()
        configManager = ConfigurationManager(
            configFile = testConfigFile.absolutePath,
            autoSave = false // 禁用自动保存以便测试
        )
    }

    @AfterEach
    fun cleanup() {
        configManager.shutdown()
        if (testConfigFile.exists()) {
            testConfigFile.delete()
        }
    }

    @Test
    fun `test set and get string config`() = runBlocking {
        configManager.setConfig("test.string", "hello world", ConfigType.STRING, "测试字符串配置")
        
        val value = configManager.getString("test.string")
        assertEquals("hello world", value)
    }

    @Test
    fun `test set and get integer config`() = runBlocking {
        configManager.setConfig("test.integer", "42", ConfigType.INTEGER, "测试整数配置")
        
        val value = configManager.getInt("test.integer")
        assertEquals(42, value)
    }

    @Test
    fun `test set and get long config`() = runBlocking {
        configManager.setConfig("test.long", "1234567890", ConfigType.LONG, "测试长整数配置")
        
        val value = configManager.getLong("test.long")
        assertEquals(1234567890L, value)
    }

    @Test
    fun `test set and get double config`() = runBlocking {
        configManager.setConfig("test.double", "3.14159", ConfigType.DOUBLE, "测试双精度配置")
        
        val value = configManager.getDouble("test.double")
        assertEquals(3.14159, value!!, 0.00001)
    }

    @Test
    fun `test set and get boolean config`() = runBlocking {
        configManager.setConfig("test.boolean.true", "true", ConfigType.BOOLEAN, "测试布尔配置")
        configManager.setConfig("test.boolean.false", "false", ConfigType.BOOLEAN, "测试布尔配置")
        configManager.setConfig("test.boolean.yes", "yes", ConfigType.BOOLEAN, "测试布尔配置")
        configManager.setConfig("test.boolean.no", "no", ConfigType.BOOLEAN, "测试布尔配置")
        
        assertTrue(configManager.getBoolean("test.boolean.true")!!)
        assertFalse(configManager.getBoolean("test.boolean.false")!!)
        assertTrue(configManager.getBoolean("test.boolean.yes")!!)
        assertFalse(configManager.getBoolean("test.boolean.no")!!)
    }

    @Test
    fun `test set and get duration config`() = runBlocking {
        configManager.setConfig("test.duration", "30s", ConfigType.DURATION, "测试时长配置")
        
        val value = configManager.getDuration("test.duration")
        assertEquals(30.seconds, value)
    }

    @Test
    fun `test set and get list config`() = runBlocking {
        configManager.setConfig("test.list", "apple,banana,orange", ConfigType.LIST, "测试列表配置")
        
        val value = configManager.getList("test.list")
        assertEquals(listOf("apple", "banana", "orange"), value)
    }

    @Test
    fun `test set and get json config`() = runBlocking {
        val jsonValue = """{"name": "test", "value": 123}"""
        configManager.setConfig("test.json", jsonValue, ConfigType.JSON, "测试JSON配置")

        val value = configManager.getJson("test.json", null) { jsonString ->
            // 简单的JSON解析，实际项目中应该使用专门的JSON库
            val map = mutableMapOf<String, Any>()
            if (jsonString.contains("\"name\": \"test\"")) {
                map["name"] = "test"
            }
            if (jsonString.contains("\"value\": 123")) {
                map["value"] = 123
            }
            map
        }
        assertNotNull(value)
        assertEquals("test", value!!["name"])
        assertEquals(123, value["value"])
    }

    @Test
    fun `test default values`() {
        assertEquals("default", configManager.getString("nonexistent", "default"))
        assertEquals(42, configManager.getInt("nonexistent", 42))
        assertEquals(123L, configManager.getLong("nonexistent", 123L))
        assertEquals(3.14, configManager.getDouble("nonexistent", 3.14))
        assertTrue(configManager.getBoolean("nonexistent", true)!!)
        assertEquals(30.seconds, configManager.getDuration("nonexistent", 30.seconds))
        assertEquals(listOf("a", "b"), configManager.getList("nonexistent", defaultValue = listOf("a", "b")))
    }

    @Test
    fun `test config validation`() = runBlocking {
        // 添加范围验证器
        configManager.addValidator("test.range", RangeValidator(1.0, 100.0))
        
        // 有效值应该成功
        configManager.setConfig("test.range", "50", ConfigType.INTEGER)
        assertEquals(50, configManager.getInt("test.range"))
        
        // 无效值应该抛出异常
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                configManager.setConfig("test.range", "150", ConfigType.INTEGER)
            }
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                configManager.setConfig("test.range", "-10", ConfigType.INTEGER)
            }
        }
    }

    @Test
    fun `test regex validation`() = runBlocking {
        // 添加邮箱格式验证器
        val emailPattern = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        configManager.addValidator("test.email", RegexValidator(emailPattern, "邮箱格式不正确"))
        
        // 有效邮箱应该成功
        configManager.setConfig("test.email", "test@example.com", ConfigType.STRING)
        assertEquals("test@example.com", configManager.getString("test.email"))
        
        // 无效邮箱应该抛出异常
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                configManager.setConfig("test.email", "invalid-email", ConfigType.STRING)
            }
        }
    }

    @Test
    fun `test config listeners`() = runBlocking {
        var changeEventReceived = false
        var receivedEvent: ConfigChangeEvent? = null
        
        val listener = object : ConfigListener {
            override suspend fun onConfigChanged(event: ConfigChangeEvent) {
                changeEventReceived = true
                receivedEvent = event
            }
        }
        
        configManager.addListener("test.listener", listener)
        configManager.setConfig("test.listener", "initial", ConfigType.STRING)
        
        // 等待事件处理
        delay(100)
        
        assertTrue(changeEventReceived)
        assertNotNull(receivedEvent)
        assertEquals("test.listener", receivedEvent!!.key)
        assertEquals("initial", receivedEvent!!.newValue)
        assertNull(receivedEvent!!.oldValue)
        
        // 测试配置更新
        changeEventReceived = false
        configManager.setConfig("test.listener", "updated", ConfigType.STRING)
        
        delay(100)
        
        assertTrue(changeEventReceived)
        assertEquals("updated", receivedEvent!!.newValue)
        assertEquals("initial", receivedEvent!!.oldValue)
    }

    @Test
    fun `test remove config`() = runBlocking {
        configManager.setConfig("test.remove", "to_be_removed", ConfigType.STRING)
        assertEquals("to_be_removed", configManager.getString("test.remove"))
        
        configManager.removeConfig("test.remove")
        assertNull(configManager.getString("test.remove"))
    }

    @Test
    fun `test get all configs`() = runBlocking {
        configManager.setConfig("test.1", "value1", ConfigType.STRING)
        configManager.setConfig("test.2", "value2", ConfigType.STRING)
        configManager.setConfig("test.3", "value3", ConfigType.STRING)
        
        val allConfigs = configManager.getAllConfigs()
        
        // 应该包含我们设置的配置和默认配置
        assertTrue(allConfigs.size >= 3)
        assertTrue(allConfigs.containsKey("test.1"))
        assertTrue(allConfigs.containsKey("test.2"))
        assertTrue(allConfigs.containsKey("test.3"))
        
        assertEquals("value1", allConfigs["test.1"]?.value)
        assertEquals("value2", allConfigs["test.2"]?.value)
        assertEquals("value3", allConfigs["test.3"]?.value)
    }

    @Test
    fun `test save and load configuration`() = runBlocking {
        // 设置一些配置
        configManager.setConfig("test.save.string", "saved_value", ConfigType.STRING, "保存测试")
        configManager.setConfig("test.save.int", "42", ConfigType.INTEGER, "保存测试")
        configManager.setConfig("test.save.bool", "true", ConfigType.BOOLEAN, "保存测试")
        
        // 手动保存
        configManager.saveConfiguration()
        
        // 验证文件存在
        assertTrue(testConfigFile.exists())
        
        // 创建新的配置管理器来测试加载
        val newConfigManager = ConfigurationManager(
            configFile = testConfigFile.absolutePath,
            autoSave = false
        )
        
        // 验证配置被正确加载
        assertEquals("saved_value", newConfigManager.getString("test.save.string"))
        assertEquals(42, newConfigManager.getInt("test.save.int"))
        assertTrue(newConfigManager.getBoolean("test.save.bool")!!)
        
        newConfigManager.shutdown()
    }

    @Test
    fun `test reload configuration`() = runBlocking {
        // 设置初始配置
        configManager.setConfig("test.reload", "initial", ConfigType.STRING)
        configManager.saveConfiguration()
        
        // 手动修改文件内容（模拟外部修改）
        val configContent = testConfigFile.readText()
        val modifiedContent = configContent.replace("initial", "modified_externally")
        testConfigFile.writeText(modifiedContent)
        
        // 重新加载配置
        configManager.reloadConfiguration()
        
        // 验证配置被更新
        assertEquals("modified_externally", configManager.getString("test.reload"))
    }

    @Test
    fun `test default configuration creation`() {
        // 删除配置文件
        if (testConfigFile.exists()) {
            testConfigFile.delete()
        }
        
        // 创建新的配置管理器
        val newConfigManager = ConfigurationManager(
            configFile = testConfigFile.absolutePath,
            autoSave = false
        )
        
        // 验证默认配置存在
        assertNotNull(newConfigManager.getString("disruptorx.node.id"))
        assertNotNull(newConfigManager.getString("disruptorx.node.host"))
        assertNotNull(newConfigManager.getInt("disruptorx.node.port"))
        assertNotNull(newConfigManager.getInt("disruptorx.ringbuffer.size"))
        
        newConfigManager.shutdown()
    }

    @Test
    fun `test invalid type conversion`() = runBlocking {
        configManager.setConfig("test.invalid", "not_a_number", ConfigType.STRING)
        
        // 尝试将字符串转换为数字应该返回默认值
        assertEquals(999, configManager.getInt("test.invalid", 999))
        assertEquals(888L, configManager.getLong("test.invalid", 888L))
        assertEquals(7.77, configManager.getDouble("test.invalid", 7.77))
    }

    @Test
    fun `test empty and null values`() = runBlocking {
        configManager.setConfig("test.empty", "", ConfigType.STRING)

        assertEquals("", configManager.getString("test.empty"))
        // 空字符串不应该使用默认值，因为它是一个有效的值
        assertEquals("", configManager.getString("test.empty", "default"))

        // 空列表
        configManager.setConfig("test.empty.list", "", ConfigType.LIST)
        assertEquals(emptyList<String>(), configManager.getList("test.empty.list"))
    }
}
