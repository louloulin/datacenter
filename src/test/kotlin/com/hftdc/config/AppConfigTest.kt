package com.hftdc.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AppConfigTest {

    @TempDir
    lateinit var tempDir: Path
    
    @AfterEach
    fun tearDown() {
        // 清除系统属性，避免影响其他测试
        System.clearProperty("env")
        System.clearProperty("config.file")
    }
    
    @Test
    fun `test loading default configuration`() {
        // 设置测试环境
        System.setProperty("config.file", createTestConfigFile("default.conf", """
            disruptor {
              buffer-size = 1024
              wait-strategy = "test"
              producer-type = "single"
            }
            akka {
              cluster-enabled = false
              seed-nodes = []
            }
            db {
              url = "jdbc:h2:mem:test"
              username = "test"
              password = "test"
              pool-size = 5
            }
            engine {
              max-orders-per-book = 100
              max-instruments = 50
              snapshot-interval = 1000
              cleanup-idle-actors-after-minutes = 5
            }
            recovery {
              enabled = false
              include-events-before-snapshot = false
              events-before-snapshot-time-window-ms = 1000
              auto-start-snapshots = false
            }
            api {
              host = "localhost"
              port = 8888
              enable-cors = false
              request-timeout-ms = 5000
            }
            monitoring {
              prometheus-enabled = false
              prometheus-port = 8000
              metrics-interval-seconds = 5
            }
        """).absolutePath)
        
        // 执行测试
        val config = AppConfig.load()
        
        // 验证配置
        assertEquals("development", config.environment)
        assertEquals(1024, config.disruptor.bufferSize)
        assertEquals("test", config.disruptor.waitStrategy)
        assertEquals("single", config.disruptor.producerType)
        assertEquals(false, config.akka.clusterEnabled)
        assertEquals("jdbc:h2:mem:test", config.db.url)
        assertEquals(100, config.engine.maxOrdersPerBook)
        assertEquals(false, config.recovery.enabled)
        assertEquals("localhost", config.api.host)
        assertEquals(8888, config.api.port)
        assertEquals(false, config.monitoring.prometheusEnabled)
        assertNull(config.performance)
        assertNull(config.security)
    }
    
    @Test
    fun `test loading production configuration`() {
        // 设置测试环境
        System.setProperty("env", "production")
        System.setProperty("config.file", createTestConfigFile("production.conf", """
            disruptor {
              buffer-size = 8192
              wait-strategy = "busySpin"
              producer-type = "multi"
            }
            akka {
              cluster-enabled = true
              seed-nodes = ["akka://hftdc@node1:2551"]
            }
            db {
              url = "jdbc:postgresql://production:5432/hftdc"
              username = "prod"
              password = "prod-password"
              pool-size = 20
            }
            engine {
              max-orders-per-book = 1000000
              max-instruments = 500
              snapshot-interval = 30000
              cleanup-idle-actors-after-minutes = 10
            }
            recovery {
              enabled = true
              include-events-before-snapshot = true
              events-before-snapshot-time-window-ms = 5000
              auto-start-snapshots = true
            }
            api {
              host = "0.0.0.0"
              port = 80
              enable-cors = true
              request-timeout-ms = 15000
            }
            monitoring {
              prometheus-enabled = true
              prometheus-port = 9090
              metrics-interval-seconds = 10
            }
            performance {
              jvm-optimizations-enabled = true
              gc-log-dir = "/data/logs/gc"
              off-heap-enabled = true
              off-heap-size-mb = 4096
            }
            security {
              ssl-enabled = true
              ssl-certificate = "/path/to/cert"
              ssl-private-key = "/path/to/key"
              api-key-auth-enabled = true
              ip-whitelist-enabled = true
              allowed-ips = ["10.0.0.1", "10.0.0.2"]
            }
        """).absolutePath)
        
        // 执行测试
        val config = AppConfig.load()
        
        // 验证配置
        assertEquals("production", config.environment)
        assertEquals(8192, config.disruptor.bufferSize)
        assertEquals("busySpin", config.disruptor.waitStrategy)
        assertEquals("multi", config.disruptor.producerType)
        assertEquals(true, config.akka.clusterEnabled)
        assertEquals(1, config.akka.seedNodes.size)
        assertEquals("jdbc:postgresql://production:5432/hftdc", config.db.url)
        assertEquals(1000000, config.engine.maxOrdersPerBook)
        assertEquals(true, config.recovery.enabled)
        assertEquals("0.0.0.0", config.api.host)
        assertEquals(80, config.api.port)
        assertEquals(true, config.monitoring.prometheusEnabled)
        assertNotNull(config.performance)
        assertEquals(true, config.performance!!.jvmOptimizationsEnabled)
        assertEquals(4096, config.performance!!.offHeapSizeMb)
        assertNotNull(config.security)
        assertEquals(true, config.security!!.sslEnabled)
        assertEquals(2, config.security!!.allowedIps.size)
    }
    
    @Test
    fun `test environment-specific configuration overrides default`() {
        // 创建一个包含默认配置和环境特定配置的测试文件
        val configFile = createTestConfigFile("combined.conf", """
            # 默认配置部分
            disruptor {
              buffer-size = 1024
              wait-strategy = "default"
              producer-type = "single"
            }
            akka {
              cluster-enabled = false
              seed-nodes = []
            }
            db {
              url = "jdbc:h2:mem:default"
              username = "default"
              password = "default"
              pool-size = 5
            }
            engine {
              max-orders-per-book = 100
              max-instruments = 50
              snapshot-interval = 1000
              cleanup-idle-actors-after-minutes = 5
            }
            recovery {
              enabled = false
              include-events-before-snapshot = false
              events-before-snapshot-time-window-ms = 1000
              auto-start-snapshots = false
            }
            api {
              host = "localhost"
              port = 8080
              enable-cors = false
              request-timeout-ms = 5000
            }
            monitoring {
              prometheus-enabled = false
              prometheus-port = 8000
              metrics-interval-seconds = 5
            }
            
            # 生产环境覆盖
            disruptor.buffer-size = 8192
            disruptor.wait-strategy = "busySpin"
            db.url = "jdbc:postgresql://production:5432/hftdc"
            db.username = "prod"
            monitoring.prometheus-enabled = true
            
            # 性能优化配置
            performance {
              jvm-optimizations-enabled = true
              gc-log-dir = "/data/logs/gc"
              off-heap-enabled = true
              off-heap-size-mb = 4096
            }
        """).absolutePath
        
        // 设置环境和配置文件
        System.setProperty("env", "production")
        System.setProperty("config.file", configFile)
        
        // 执行测试
        val config = AppConfig.load()
        
        // 验证合并后的配置
        assertEquals("production", config.environment)
        assertEquals(8192, config.disruptor.bufferSize)
        assertEquals("busySpin", config.disruptor.waitStrategy)
        assertEquals("single", config.disruptor.producerType)  // 从默认配置继承
        assertEquals(false, config.akka.clusterEnabled)  // 从默认配置继承
        assertEquals("jdbc:postgresql://production:5432/hftdc", config.db.url)
        assertEquals("prod", config.db.username)
        assertEquals("default", config.db.password)  // 从默认配置继承
        assertEquals(5, config.db.poolSize)  // 从默认配置继承
        assertEquals(true, config.monitoring.prometheusEnabled)
        assertEquals(8000, config.monitoring.prometheusPort)  // 从默认配置继承
        assertNotNull(config.performance)
        assertEquals(true, config.performance!!.jvmOptimizationsEnabled)
        assertEquals(4096, config.performance!!.offHeapSizeMb)
        assertNull(config.security)  // 不存在于任何配置中
    }
    
    /**
     * 创建测试配置文件
     */
    private fun createTestConfigFile(fileName: String, content: String): File {
        val configFile = tempDir.resolve(fileName).toFile()
        configFile.writeText(content)
        return configFile
    }
} 