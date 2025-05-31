package com.hftdc.disruptorx.config

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 配置项
 */
@Serializable
data class ConfigItem(
    val key: String,
    val value: String,
    val type: ConfigType,
    val description: String = "",
    val defaultValue: String? = null,
    val validationRule: String? = null,
    val lastModified: Long = System.currentTimeMillis(),
    val modifiedBy: String = "system"
)

/**
 * 配置类型
 */
@Serializable
enum class ConfigType {
    STRING,
    INTEGER,
    LONG,
    DOUBLE,
    BOOLEAN,
    DURATION,
    LIST,
    JSON
}

/**
 * 配置变更事件
 */
data class ConfigChangeEvent(
    val key: String,
    val oldValue: String?,
    val newValue: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 配置监听器
 */
interface ConfigListener {
    suspend fun onConfigChanged(event: ConfigChangeEvent)
}

/**
 * 配置验证器
 */
interface ConfigValidator {
    fun validate(key: String, value: String): Boolean
    fun getErrorMessage(): String
}

/**
 * 配置管理器
 */
class ConfigurationManager(
    private val configFile: String = "disruptorx-config.json",
    private val autoSave: Boolean = true,
    private val saveInterval: Duration = 30.seconds
) {
    private val configs = ConcurrentHashMap<String, ConfigItem>()
    private val listeners = ConcurrentHashMap<String, MutableList<ConfigListener>>()
    private val validators = ConcurrentHashMap<String, ConfigValidator>()
    private val mutex = Mutex()
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    // 自动保存任务
    private var autoSaveJob: Job? = null
    
    init {
        loadConfiguration()
        if (autoSave) {
            startAutoSave()
        }
    }
    
    /**
     * 设置配置项
     */
    suspend fun setConfig(
        key: String,
        value: String,
        type: ConfigType = ConfigType.STRING,
        description: String = "",
        modifiedBy: String = "system"
    ) {
        mutex.withLock {
            // 验证配置值
            val validator = validators[key]
            if (validator != null && !validator.validate(key, value)) {
                throw IllegalArgumentException("配置验证失败: ${validator.getErrorMessage()}")
            }
            
            val oldValue = configs[key]?.value
            val configItem = ConfigItem(
                key = key,
                value = value,
                type = type,
                description = description,
                lastModified = System.currentTimeMillis(),
                modifiedBy = modifiedBy
            )
            
            configs[key] = configItem
            
            // 通知监听器
            val event = ConfigChangeEvent(key, oldValue, value)
            notifyListeners(key, event)
            
            // 立即保存（如果启用了自动保存）
            if (autoSave) {
                saveConfiguration()
            }
        }
    }
    
    /**
     * 获取字符串配置
     */
    fun getString(key: String, defaultValue: String? = null): String? {
        val config = configs[key]
        return when {
            config != null -> config.value
            defaultValue != null -> defaultValue
            else -> null
        }
    }
    
    /**
     * 获取整数配置
     */
    fun getInt(key: String, defaultValue: Int? = null): Int? {
        val value = getString(key) ?: return defaultValue
        return try {
            value.toInt()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }
    
    /**
     * 获取长整数配置
     */
    fun getLong(key: String, defaultValue: Long? = null): Long? {
        val value = getString(key) ?: return defaultValue
        return try {
            value.toLong()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }
    
    /**
     * 获取双精度配置
     */
    fun getDouble(key: String, defaultValue: Double? = null): Double? {
        val value = getString(key) ?: return defaultValue
        return try {
            value.toDouble()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }
    
    /**
     * 获取布尔配置
     */
    fun getBoolean(key: String, defaultValue: Boolean? = null): Boolean? {
        val value = getString(key) ?: return defaultValue
        return when (value.lowercase()) {
            "true", "yes", "1", "on" -> true
            "false", "no", "0", "off" -> false
            else -> defaultValue
        }
    }
    
    /**
     * 获取时长配置
     */
    fun getDuration(key: String, defaultValue: Duration? = null): Duration? {
        val value = getString(key) ?: return defaultValue
        return try {
            Duration.parse(value)
        } catch (e: Exception) {
            defaultValue
        }
    }
    
    /**
     * 获取列表配置
     */
    fun getList(key: String, delimiter: String = ",", defaultValue: List<String>? = null): List<String>? {
        val value = getString(key) ?: return defaultValue
        return if (value.isBlank()) {
            emptyList()
        } else {
            value.split(delimiter).map { it.trim() }
        }
    }
    
    /**
     * 获取JSON配置
     */
    fun <T> getJson(key: String, defaultValue: T? = null, deserializer: (String) -> T): T? {
        val value = getString(key) ?: return defaultValue
        return try {
            deserializer(value)
        } catch (e: Exception) {
            defaultValue
        }
    }
    
    /**
     * 删除配置项
     */
    suspend fun removeConfig(key: String, modifiedBy: String = "system") {
        mutex.withLock {
            val oldValue = configs[key]?.value
            configs.remove(key)
            
            if (oldValue != null) {
                val event = ConfigChangeEvent(key, oldValue, "")
                notifyListeners(key, event)
            }
            
            if (autoSave) {
                saveConfiguration()
            }
        }
    }
    
    /**
     * 获取所有配置
     */
    fun getAllConfigs(): Map<String, ConfigItem> {
        return configs.toMap()
    }
    
    /**
     * 添加配置监听器
     */
    fun addListener(key: String, listener: ConfigListener) {
        listeners.computeIfAbsent(key) { mutableListOf() }.add(listener)
    }
    
    /**
     * 移除配置监听器
     */
    fun removeListener(key: String, listener: ConfigListener) {
        listeners[key]?.remove(listener)
    }
    
    /**
     * 添加配置验证器
     */
    fun addValidator(key: String, validator: ConfigValidator) {
        validators[key] = validator
    }
    
    /**
     * 移除配置验证器
     */
    fun removeValidator(key: String) {
        validators.remove(key)
    }
    
    /**
     * 重新加载配置
     */
    suspend fun reloadConfiguration() {
        mutex.withLock {
            loadConfiguration()
        }
    }
    
    /**
     * 手动保存配置
     */
    suspend fun saveConfiguration() {
        mutex.withLock {
            try {
                val configList = configs.values.toList()
                val jsonString = json.encodeToString(configList)
                File(configFile).writeText(jsonString)
            } catch (e: Exception) {
                println("保存配置失败: ${e.message}")
            }
        }
    }
    
    /**
     * 加载配置
     */
    private fun loadConfiguration() {
        try {
            val file = File(configFile)
            if (file.exists()) {
                val jsonString = file.readText()
                val configList = json.decodeFromString<List<ConfigItem>>(jsonString)
                
                configs.clear()
                configList.forEach { config ->
                    configs[config.key] = config
                }
                
                println("配置加载成功，共 ${configs.size} 项配置")
            } else {
                // 创建默认配置
                createDefaultConfiguration()
            }
        } catch (e: Exception) {
            println("加载配置失败: ${e.message}")
            createDefaultConfiguration()
        }
    }
    
    /**
     * 创建默认配置
     */
    private fun createDefaultConfiguration() {
        val defaultConfigs = listOf(
            ConfigItem("disruptorx.node.id", "node-1", ConfigType.STRING, "节点ID"),
            ConfigItem("disruptorx.node.host", "localhost", ConfigType.STRING, "节点主机"),
            ConfigItem("disruptorx.node.port", "9090", ConfigType.INTEGER, "节点端口"),
            ConfigItem("disruptorx.ringbuffer.size", "1024", ConfigType.INTEGER, "环形缓冲区大小"),
            ConfigItem("disruptorx.event.batch.size", "100", ConfigType.INTEGER, "事件批处理大小"),
            ConfigItem("disruptorx.network.timeout", "30s", ConfigType.DURATION, "网络超时时间"),
            ConfigItem("disruptorx.security.enabled", "true", ConfigType.BOOLEAN, "是否启用安全认证"),
            ConfigItem("disruptorx.metrics.enabled", "true", ConfigType.BOOLEAN, "是否启用指标收集"),
            ConfigItem("disruptorx.tracing.enabled", "true", ConfigType.BOOLEAN, "是否启用分布式追踪")
        )
        
        defaultConfigs.forEach { config ->
            configs[config.key] = config
        }
    }
    
    /**
     * 通知监听器
     */
    private suspend fun notifyListeners(key: String, event: ConfigChangeEvent) {
        val keyListeners = listeners[key] ?: return
        
        keyListeners.forEach { listener ->
            try {
                listener.onConfigChanged(event)
            } catch (e: Exception) {
                println("配置监听器通知失败: ${e.message}")
            }
        }
    }
    
    /**
     * 启动自动保存
     */
    private fun startAutoSave() {
        autoSaveJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(saveInterval)
                try {
                    saveConfiguration()
                } catch (e: Exception) {
                    println("自动保存配置失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 关闭配置管理器
     */
    fun shutdown() {
        autoSaveJob?.cancel()
        runBlocking {
            if (autoSave) {
                saveConfiguration()
            }
        }
    }
}

/**
 * 范围验证器
 */
class RangeValidator(
    private val min: Double,
    private val max: Double
) : ConfigValidator {
    private var errorMessage = ""
    
    override fun validate(key: String, value: String): Boolean {
        return try {
            val numValue = value.toDouble()
            val isValid = numValue >= min && numValue <= max
            if (!isValid) {
                errorMessage = "值必须在 $min 到 $max 之间"
            }
            isValid
        } catch (e: NumberFormatException) {
            errorMessage = "值必须是有效的数字"
            false
        }
    }
    
    override fun getErrorMessage(): String = errorMessage
}

/**
 * 正则表达式验证器
 */
class RegexValidator(
    private val pattern: Regex,
    private val message: String = "值格式不正确"
) : ConfigValidator {
    
    override fun validate(key: String, value: String): Boolean {
        return pattern.matches(value)
    }
    
    override fun getErrorMessage(): String = message
}
