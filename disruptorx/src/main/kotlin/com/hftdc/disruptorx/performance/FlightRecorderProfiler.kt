package com.hftdc.disruptorx.performance

import jdk.jfr.Configuration
import jdk.jfr.FlightRecorder
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 飞行记录器（Flight Recorder）分析工具
 * 使用JDK自带的JFR进行深度性能分析
 */
class FlightRecorderProfiler(
    private val name: String,
    private val outputDirectory: String = "jfr-results"
) {
    // JFR录制实例
    private var recording: Recording? = null
    
    // 默认录制持续时间 (秒)
    private val defaultDuration = 60L
    
    // 开始和结束时间
    private var startTime: Instant? = null
    private var endTime: Instant? = null
    
    /**
     * 启动JFR录制
     * @param durationSeconds 录制持续时间(秒)
     * @param settings JFR配置名称 ("default", "profile")
     */
    fun start(durationSeconds: Long = defaultDuration, settings: String = "profile") {
        try {
            println("启动JFR录制: $name, 配置: $settings, 持续时间: ${durationSeconds}秒")
            
            // 确保FlightRecorder可用
            if (!FlightRecorder.isAvailable()) {
                println("警告: JFR不可用，请使用JDK11+并添加启动参数: -XX:+FlightRecorder")
                return
            }
            
            // 创建录制目录
            val dir = File(outputDirectory)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            
            // 生成录制文件路径
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            val recordingFile = File(dir, "${name.replace(" ", "_")}_$timestamp.jfr")
            
            // 加载配置
            val config = Configuration.getConfiguration(settings)
            
            // 创建录制
            val recording = Recording(config)
            recording.name = "$name-$timestamp"
            
            // 设置持续时间
            if (durationSeconds > 0) {
                recording.duration = Duration.ofSeconds(durationSeconds)
            }
            
            // 设置输出文件
            recording.destination = recordingFile.toPath()
            
            // 记录开始时间
            startTime = Instant.now()
            
            // 启动录制
            recording.start()
            this.recording = recording
            
            println("JFR录制已启动，将保存到: ${recordingFile.absolutePath}")
            
        } catch (e: Exception) {
            println("启动JFR录制失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 停止录制并生成报告
     */
    fun stop(): JfrAnalysisReport? {
        try {
            val recording = this.recording ?: return null
            
            // 如果录制正在进行
            if (recording.state == Recording.State.RUNNING) {
                // 记录结束时间
                endTime = Instant.now()
                
                // 停止录制
                recording.stop()
                println("JFR录制已停止")
                
                // 获取录制文件路径
                val recordingPath = recording.destination
                
                // 关闭录制
                recording.close()
                
                // 分析录制结果
                if (recordingPath != null) {
                    return analyzeRecording(recordingPath)
                }
            }
        } catch (e: Exception) {
            println("停止JFR录制失败: ${e.message}")
            e.printStackTrace()
        }
        
        return null
    }
    
    /**
     * 分析JFR录制文件
     */
    private fun analyzeRecording(recordingPath: Path): JfrAnalysisReport {
        println("分析JFR录制文件: $recordingPath")
        
        // 存储分析结果
        var gcPauseTime = 0L
        var longestGcPause = 0L
        var threadContextSwitches = 0L
        var classLoadingCount = 0
        var compilationTime = 0L
        var allocatedMemory = 0L
        var highestCpuLoad = 0.0
        var socketReadBytes = 0L
        var socketWriteBytes = 0L
        
        // 加载JFR文件进行分析
        RecordingFile.readAllEvents(recordingPath).use { events ->
            events.forEach { event ->
                when (event.eventType.name) {
                    // GC事件
                    "jdk.GarbageCollection" -> {
                        val duration = event.getLong("duration")
                        gcPauseTime += duration
                        if (duration > longestGcPause) {
                            longestGcPause = duration
                        }
                    }
                    
                    // 线程上下文切换
                    "jdk.ThreadCPULoad" -> {
                        val user = event.getDouble("user")
                        if (user > highestCpuLoad) {
                            highestCpuLoad = user
                        }
                    }
                    
                    // 类加载
                    "jdk.ClassLoad" -> {
                        classLoadingCount++
                    }
                    
                    // JIT编译
                    "jdk.Compilation" -> {
                        compilationTime += event.getLong("compileTime")
                    }
                    
                    // 对象分配
                    "jdk.ObjectAllocationSample" -> {
                        allocatedMemory += event.getLong("weight")
                    }
                    
                    // 网络IO
                    "jdk.SocketRead" -> {
                        socketReadBytes += event.getLong("bytesRead")
                    }
                    "jdk.SocketWrite" -> {
                        socketWriteBytes += event.getLong("bytesWritten")
                    }
                }
            }
        }
        
        // 计算录制持续时间
        val durationMs = if (startTime != null && endTime != null) {
            Duration.between(startTime, endTime).toMillis()
        } else {
            0L
        }
        
        // 创建报告
        val report = JfrAnalysisReport(
            name = name,
            recordingPath = recordingPath.toString(),
            durationMs = durationMs,
            gcPauseTimeMs = gcPauseTime / 1_000_000, // 纳秒转毫秒
            longestGcPauseMs = longestGcPause / 1_000_000, // 纳秒转毫秒
            threadContextSwitches = threadContextSwitches,
            classLoadingCount = classLoadingCount,
            compilationTimeMs = compilationTime / 1_000_000, // 纳秒转毫秒
            allocatedMemoryMB = allocatedMemory / (1024 * 1024), // 字节转MB
            highestCpuLoad = highestCpuLoad,
            socketReadMB = socketReadBytes / (1024 * 1024), // 字节转MB
            socketWriteMB = socketWriteBytes / (1024 * 1024) // 字节转MB
        )
        
        // 保存报告
        saveReport(report)
        
        return report
    }
    
    /**
     * 保存分析报告
     */
    private fun saveReport(report: JfrAnalysisReport) {
        try {
            val reportFile = File(outputDirectory, "${report.name.replace(" ", "_")}_jfr_report.txt")
            
            reportFile.printWriter().use { writer ->
                writer.println("====== JFR分析报告: ${report.name} ======")
                writer.println("录制文件: ${report.recordingPath}")
                writer.println("录制时长: ${report.durationMs} 毫秒")
                writer.println()
                writer.println("GC统计:")
                writer.println("  GC暂停总时间: ${report.gcPauseTimeMs} 毫秒")
                writer.println("  最长GC暂停: ${report.longestGcPauseMs} 毫秒")
                writer.println()
                writer.println("线程与CPU:")
                writer.println("  线程上下文切换: ${report.threadContextSwitches} 次")
                writer.println("  最高CPU负载: ${report.highestCpuLoad * 100} %")
                writer.println()
                writer.println("类加载与编译:")
                writer.println("  类加载数量: ${report.classLoadingCount}")
                writer.println("  JIT编译时间: ${report.compilationTimeMs} 毫秒")
                writer.println()
                writer.println("内存:")
                writer.println("  分配内存: ${report.allocatedMemoryMB} MB")
                writer.println()
                writer.println("网络IO:")
                writer.println("  读取数据: ${report.socketReadMB} MB")
                writer.println("  写入数据: ${report.socketWriteMB} MB")
            }
            
            println("JFR分析报告已保存到: ${reportFile.absolutePath}")
            
        } catch (e: Exception) {
            println("保存JFR分析报告失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * JFR分析报告数据类
     */
    data class JfrAnalysisReport(
        val name: String,
        val recordingPath: String,
        val durationMs: Long,
        val gcPauseTimeMs: Long,
        val longestGcPauseMs: Long,
        val threadContextSwitches: Long,
        val classLoadingCount: Int,
        val compilationTimeMs: Long,
        val allocatedMemoryMB: Long,
        val highestCpuLoad: Double,
        val socketReadMB: Long,
        val socketWriteMB: Long
    )
    
    companion object {
        /**
         * 检查JFR是否可用
         */
        @JvmStatic
        fun isAvailable(): Boolean {
            return try {
                FlightRecorder.isAvailable()
            } catch (e: Exception) {
                false
            }
        }
        
        /**
         * 以默认设置进行快速录制
         */
        @JvmStatic
        fun quickRecord(name: String, durationSeconds: Long = 30): JfrAnalysisReport? {
            val profiler = FlightRecorderProfiler(name)
            profiler.start(durationSeconds)
            
            // 等待录制完成
            Thread.sleep(durationSeconds * 1000 + 1000) // 多等1秒确保录制完成
            
            return profiler.stop()
        }
    }
} 