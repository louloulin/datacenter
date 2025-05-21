package com.hftdc.analytics.dsl

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 分析管道DSL注解
 */
@DslMarker
annotation class AnalyticsPipelineDsl

/**
 * 分析管道定义
 */
@AnalyticsPipelineDsl
class AnalyticsPipeline(val name: String) {
    private val stages = mutableListOf<PipelineStage>()
    private var source: SourceStage? = null
    private var sinks = mutableListOf<SinkStage>()
    
    fun source(init: SourceStage.() -> Unit) {
        source = SourceStage().apply(init)
    }
    
    fun transform(name: String = "", init: TransformStage.() -> Unit) {
        stages.add(TransformStage(name).apply(init))
    }
    
    fun window(init: WindowStage.() -> Unit) {
        stages.add(WindowStage().apply(init))
    }
    
    fun aggregate(init: AggregateStage.() -> Unit) {
        stages.add(AggregateStage().apply(init))
    }
    
    fun filter(init: FilterStage.() -> Unit) {
        stages.add(FilterStage().apply(init))
    }
    
    fun sink(init: SinkStage.() -> Unit) {
        sinks.add(SinkStage().apply(init))
    }
    
    fun validate(): Boolean {
        // 确保管道配置有效
        if (source == null) {
            throw IllegalStateException("分析管道必须包含数据源")
        }
        
        if (sinks.isEmpty()) {
            throw IllegalStateException("分析管道必须包含至少一个输出目标")
        }
        
        return true
    }
    
    fun getStages(): List<PipelineStage> = stages.toList()
    fun getSource(): SourceStage = source ?: throw IllegalStateException("未定义数据源")
    fun getSinks(): List<SinkStage> = sinks.toList()
}

/**
 * 管道阶段接口
 */
interface PipelineStage

/**
 * 数据源阶段
 */
@AnalyticsPipelineDsl
class SourceStage : PipelineStage {
    var topic: String = ""
    var format: String = "json"
    var partitions: List<Int> = emptyList()
    
    fun fromTopic(topicName: String) {
        topic = topicName
    }
    
    fun fromOrderEvents() {
        topic = "orders"
        format = "protobuf"
    }
    
    fun fromTradeEvents() {
        topic = "trades"
        format = "protobuf"
    }
    
    fun fromMarketData() {
        topic = "marketData"
        format = "protobuf"
    }
    
    fun fromOrderBook() {
        topic = "orderBookUpdates"
        format = "protobuf"
    }
}

/**
 * 转换阶段
 */
@AnalyticsPipelineDsl
class TransformStage(val name: String) : PipelineStage {
    private val selectedFields = mutableListOf<String>()
    private val computedFields = mutableMapOf<String, String>()
    private val transformations = mutableListOf<String>()
    
    fun select(vararg fields: String) {
        selectedFields.addAll(fields)
    }
    
    fun withField(name: String, expression: String) {
        computedFields[name] = expression
    }
    
    fun extractMarketDepth() {
        transformations.add("extractMarketDepth")
    }
    
    fun calculateVwap() {
        transformations.add("calculateVwap")
    }
    
    fun calculateOrderImbalance() {
        transformations.add("calculateOrderImbalance")
    }
    
    fun normalizePrice() {
        transformations.add("normalizePrice")
    }
    
    fun getSelectedFields(): List<String> = selectedFields.toList()
    fun getComputedFields(): Map<String, String> = computedFields.toMap()
    fun getTransformations(): List<String> = transformations.toList()
}

/**
 * 窗口阶段
 */
@AnalyticsPipelineDsl
class WindowStage : PipelineStage {
    private var windowType: WindowType = WindowType.TUMBLING
    private var windowDuration: Duration = 1.minutes
    private var slidingInterval: Duration = 30.seconds
    private val groupByFields = mutableListOf<String>()
    
    fun tumbling(duration: Duration) {
        windowType = WindowType.TUMBLING
        windowDuration = duration
    }
    
    fun sliding(duration: Duration, slide: Duration) {
        windowType = WindowType.SLIDING
        windowDuration = duration
        slidingInterval = slide
    }
    
    fun session(gap: Duration) {
        windowType = WindowType.SESSION
        windowDuration = gap
    }
    
    fun groupBy(vararg fields: String) {
        groupByFields.addAll(fields)
    }
    
    fun getWindowType(): WindowType = windowType
    fun getWindowDuration(): Duration = windowDuration
    fun getSlidingInterval(): Duration = slidingInterval
    fun getGroupByFields(): List<String> = groupByFields.toList()
}

/**
 * 窗口类型
 */
enum class WindowType {
    TUMBLING,  // 翻滚窗口，固定大小不重叠
    SLIDING,   // 滑动窗口，固定大小可重叠
    SESSION    // 会话窗口，由活动边界定义
}

/**
 * 聚合阶段
 */
@AnalyticsPipelineDsl
class AggregateStage : PipelineStage {
    private val aggregations = mutableListOf<AggregationDef>()
    
    fun count(): AggregationBuilder = AggregationBuilder("count", "*").also { aggregations.add(it.build()) }
    
    fun sum(field: String): AggregationBuilder = AggregationBuilder("sum", field).also { aggregations.add(it.build()) }
    
    fun avg(field: String): AggregationBuilder = AggregationBuilder("avg", field).also { aggregations.add(it.build()) }
    
    fun min(field: String): AggregationBuilder = AggregationBuilder("min", field).also { aggregations.add(it.build()) }
    
    fun max(field: String): AggregationBuilder = AggregationBuilder("max", field).also { aggregations.add(it.build()) }
    
    fun getAggregations(): List<AggregationDef> = aggregations.toList()
    
    // 高级聚合
    fun calculateImbalance() {
        aggregations.add(AggregationDef("marketImbalance", "calculateImbalance", "price,quantity,side"))
    }
    
    fun detectPressureLevels() {
        aggregations.add(AggregationDef("pressureLevels", "detectPressureLevels", "price,quantity"))
    }
}

/**
 * 聚合定义
 */
data class AggregationDef(
    val outputField: String,
    val function: String,
    val inputField: String
)

/**
 * 聚合构建器
 */
class AggregationBuilder(private val function: String, private val field: String) {
    private var alias: String = ""
    
    fun as(outputName: String): AggregationDef {
        alias = outputName
        return build()
    }
    
    fun build(): AggregationDef {
        return AggregationDef(alias.ifEmpty { "${function}_$field" }, function, field)
    }
}

/**
 * 过滤阶段
 */
@AnalyticsPipelineDsl
class FilterStage : PipelineStage {
    private var condition: String = ""
    
    fun where(conditionExpr: String) {
        condition = conditionExpr
    }
    
    fun getCondition(): String = condition
}

/**
 * 输出阶段
 */
@AnalyticsPipelineDsl
class SinkStage : PipelineStage {
    private val outputs = mutableListOf<OutputDef>()
    private val alerts = mutableListOf<AlertDef>()
    
    fun toTopic(topicName: String) {
        outputs.add(OutputDef("topic", topicName))
    }
    
    fun toTimeSeries(measurement: String) {
        outputs.add(OutputDef("timeseries", measurement))
    }
    
    fun toFile(path: String, format: String = "csv") {
        outputs.add(OutputDef("file", path, mapOf("format" to format)))
    }
    
    fun toDatabase(table: String, connectionId: String = "default") {
        outputs.add(OutputDef("database", table, mapOf("connectionId" to connectionId)))
    }
    
    fun toAlert(condition: String, init: AlertBuilder.() -> Unit) {
        val builder = AlertBuilder(condition).apply(init)
        alerts.add(builder.build())
    }
    
    fun getOutputs(): List<OutputDef> = outputs.toList()
    fun getAlerts(): List<AlertDef> = alerts.toList()
}

/**
 * 输出定义
 */
data class OutputDef(
    val type: String,
    val target: String,
    val options: Map<String, String> = emptyMap()
)

/**
 * 告警定义
 */
data class AlertDef(
    val condition: String,
    val message: String,
    val severity: AlertSeverity,
    val throttleMs: Long = 300000  // 默认5分钟
)

/**
 * 告警构建器
 */
@AnalyticsPipelineDsl
class AlertBuilder(private val condition: String) {
    var message: String = "Alert condition triggered"
    var severity: AlertSeverity = AlertSeverity.MEDIUM
    var throttleMs: Long = 300000
    
    fun build(): AlertDef {
        return AlertDef(condition, message, severity, throttleMs)
    }
}

/**
 * 告警严重性级别
 */
enum class AlertSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * 分析引擎DSL接口
 */
interface AnalyticsEngine {
    fun registerPipeline(init: () -> AnalyticsPipeline)
    fun startPipeline(name: String)
    fun stopPipeline(name: String)
    fun listPipelines(): List<String>
}

/**
 * 创建分析管道的函数
 */
fun analyticsEngine(init: AnalyticsEngineDsl.() -> Unit): AnalyticsEngineDsl {
    return AnalyticsEngineDsl().apply(init)
}

/**
 * 分析引擎DSL
 */
@AnalyticsPipelineDsl
class AnalyticsEngineDsl {
    private val pipelines = mutableMapOf<String, AnalyticsPipeline>()
    
    fun pipeline(name: String, init: AnalyticsPipeline.() -> Unit) {
        val pipeline = AnalyticsPipeline(name).apply(init)
        // 验证管道配置
        pipeline.validate() 
        pipelines[name] = pipeline
    }
    
    fun getPipelines(): Map<String, AnalyticsPipeline> = pipelines.toMap()
}

/**
 * 使用示例
 */
fun exampleAnalyticsPipeline() {
    val analytics = analyticsEngine {
        // 订单流分析管道
        pipeline("orderFlowAnalysis") {
            source {
                fromTopic("orders")
                format = "json"
            }
            
            transform("extractFields") {
                select("timestamp", "orderId", "userId", "instrumentId", "price", "quantity", "side")
                withField("orderValue", "price * quantity")
            }
            
            window {
                tumbling(duration = 1.minutes)
                groupBy("instrumentId", "side")
            }
            
            aggregate {
                count().as("orderCount")
                sum("quantity").as("totalQuantity")
                avg("price").as("averagePrice")
                sum("orderValue").as("totalValue")
            }
            
            filter {
                where("orderCount > 100")
            }
            
            sink {
                toTopic("orderAnalytics")
                toTimeSeries("marketActivity")
                toAlert(condition = "totalQuantity > 1000000") {
                    message = "High volume detected for #{instrumentId}"
                    severity = AlertSeverity.MEDIUM
                }
            }
        }
        
        // 市场深度分析管道
        pipeline("marketDepthAnalysis") {
            source {
                fromOrderBook()
            }
            
            transform {
                extractMarketDepth()
            }
            
            window {
                sliding(duration = 5.minutes, slide = 30.seconds)
                groupBy("instrumentId")
            }
            
            aggregate {
                calculateImbalance()
                detectPressureLevels()
            }
            
            sink {
                toTimeSeries("marketDepthMetrics")
                toTopic("marketDepthAlerts")
                toAlert(condition = "pressureLevels.buyPressure > 0.8") {
                    message = "Strong buying pressure detected for #{instrumentId}"
                    severity = AlertSeverity.HIGH
                }
            }
        }
    }
    
    // 在实际应用中，将这些分析管道注册到分析引擎
    // analyticEngine.registerPipelines(analytics.getPipelines())
} 