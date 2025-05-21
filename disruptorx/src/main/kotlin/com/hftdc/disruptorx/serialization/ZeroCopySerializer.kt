package com.hftdc.disruptorx.serialization

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 零拷贝序列化器
 * 
 * 提供高性能的序列化/反序列化功能，支持零拷贝操作，减少不必要的数据拷贝
 * 主要特性：
 * 1. 直接内存访问：直接操作堆外内存，避免Java堆与本地内存之间的拷贝
 * 2. 部分序列化：支持仅序列化数据模型的变化部分
 * 3. 共享内存池：使用预分配的ByteBuffer池，减少内存分配开销
 * 4. 高效类型处理：使用预编译的类型处理器，避免反射开销
 */
class ZeroCopySerializer {

    /**
     * 序列化上下文，包含所有序列化所需状态
     */
    class SerializationContext(
        val buffer: ByteBuffer,
        val objectReferences: MutableMap<Any, Int> = mutableMapOf(),
        var currentPosition: Int = 0
    )
    
    /**
     * 反序列化上下文，包含所有反序列化所需状态
     */
    class DeserializationContext(
        val buffer: ByteBuffer,
        val objectReferences: MutableMap<Int, Any> = mutableMapOf(),
        var currentPosition: Int = 0
    )
    
    // 类型处理器注册表
    private val typeHandlers = ConcurrentHashMap<Class<*>, TypeHandler<*>>()
    
    // 共享ByteBuffer池
    private val bufferPool = BufferPool()
    
    /**
     * 将对象序列化到ByteBuffer
     * 
     * @param obj 要序列化的对象
     * @return 包含序列化数据的ByteBuffer
     */
    fun <T : Any> serialize(obj: T): ByteBuffer {
        val buffer = bufferPool.acquire(calculateSize(obj))
        val context = SerializationContext(buffer)
        
        // 写入类型信息
        val typeName = obj.javaClass.name
        writeString(context, typeName)
        
        // 使用类型处理器序列化对象
        val handler = getTypeHandler(obj.javaClass)
        @Suppress("UNCHECKED_CAST")
        (handler as TypeHandler<T>).write(context, obj)
        
        // 准备返回
        buffer.flip()
        return buffer
    }
    
    /**
     * 从ByteBuffer反序列化对象
     * 
     * @param buffer 包含序列化数据的ByteBuffer
     * @return 反序列化的对象
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> deserialize(buffer: ByteBuffer): T {
        val context = DeserializationContext(buffer)
        
        // 读取类型信息
        val typeName = readString(context)
        val type = Class.forName(typeName)
        
        // 使用类型处理器反序列化对象
        val handler = getTypeHandler(type)
        return handler.read(context) as T
    }
    
    /**
     * 部分序列化 - 仅序列化对象中已更改的字段
     * 
     * @param obj 要序列化的对象
     * @param baseline 基准对象（未更改状态）
     * @return 包含增量序列化数据的ByteBuffer
     */
    fun <T : Any> serializeDelta(obj: T, baseline: T): ByteBuffer {
        val buffer = bufferPool.acquire(calculateSize(obj) / 2) // 估计增量大小
        val context = SerializationContext(buffer)
        
        // 写入类型信息
        val typeName = obj.javaClass.name
        writeString(context, typeName)
        
        // 使用类型处理器序列化增量
        val handler = getTypeHandler(obj.javaClass)
        @Suppress("UNCHECKED_CAST")
        (handler as DeltaTypeHandler<T>).writeDelta(context, obj, baseline)
        
        // 准备返回
        buffer.flip()
        return buffer
    }
    
    /**
     * 增量反序列化 - 根据增量更新现有对象
     * 
     * @param buffer 包含增量序列化数据的ByteBuffer
     * @param baseline 要更新的基准对象
     * @return 更新后的对象
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> deserializeDelta(buffer: ByteBuffer, baseline: T): T {
        val context = DeserializationContext(buffer)
        
        // 读取类型信息
        val typeName = readString(context)
        val type = Class.forName(typeName)
        
        // 使用类型处理器反序列化增量
        val handler = getTypeHandler(type)
        return (handler as DeltaTypeHandler<T>).readDelta(context, baseline)
    }
    
    /**
     * 获取类型的处理器，如果不存在则创建
     */
    private fun <T : Any> getTypeHandler(type: Class<T>): TypeHandler<T> {
        @Suppress("UNCHECKED_CAST")
        return typeHandlers.computeIfAbsent(type) { 
            createTypeHandler(it)
        } as TypeHandler<T>
    }
    
    /**
     * 创建类型的处理器
     */
    private fun <T : Any> createTypeHandler(type: Class<T>): TypeHandler<T> {
        // 检查内置处理器
        when {
            type == String::class.java -> 
                @Suppress("UNCHECKED_CAST")
                return StringTypeHandler() as TypeHandler<T>
            type == Int::class.javaObjectType || type == Int::class.javaPrimitiveType -> 
                @Suppress("UNCHECKED_CAST")
                return IntTypeHandler() as TypeHandler<T>
            type == Long::class.javaObjectType || type == Long::class.javaPrimitiveType -> 
                @Suppress("UNCHECKED_CAST")
                return LongTypeHandler() as TypeHandler<T>
            type == Double::class.javaObjectType || type == Double::class.javaPrimitiveType -> 
                @Suppress("UNCHECKED_CAST")
                return DoubleTypeHandler() as TypeHandler<T>
            type == Boolean::class.javaObjectType || type == Boolean::class.javaPrimitiveType -> 
                @Suppress("UNCHECKED_CAST")
                return BooleanTypeHandler() as TypeHandler<T>
            type.isArray -> 
                return ArrayTypeHandler(type.componentType) as TypeHandler<T>
            List::class.java.isAssignableFrom(type) -> 
                @Suppress("UNCHECKED_CAST")
                return ListTypeHandler() as TypeHandler<T>
            Map::class.java.isAssignableFrom(type) -> 
                @Suppress("UNCHECKED_CAST")
                return MapTypeHandler() as TypeHandler<T>
        }
        
        // 创建自定义类型处理器
        return ObjectTypeHandler(type)
    }
    
    /**
     * 估计对象序列化后的大小
     */
    private fun calculateSize(obj: Any): Int {
        // 默认大小
        var size = 256
        
        when (obj) {
            is String -> size = obj.length * 2 + 8
            is ByteArray -> size = obj.size + 8
            is IntArray -> size = obj.size * 4 + 8
            is LongArray -> size = obj.size * 8 + 8
            is DoubleArray -> size = obj.size * 8 + 8
            is Collection<*> -> size = obj.size * 64 + 16
            is Map<*, *> -> size = obj.size * 128 + 16
        }
        
        return size
    }
    
    /**
     * 写入字符串到序列化上下文
     */
    fun writeString(context: SerializationContext, value: String) {
        // 写入字符串长度
        val bytes = value.toByteArray(Charsets.UTF_8)
        context.buffer.putInt(bytes.size)
        
        // 写入字符串内容
        context.buffer.put(bytes)
        context.currentPosition += 4 + bytes.size
    }
    
    /**
     * 从反序列化上下文读取字符串
     */
    fun readString(context: DeserializationContext): String {
        // 读取字符串长度
        val length = context.buffer.getInt()
        
        // 读取字符串内容
        val bytes = ByteArray(length)
        context.buffer.get(bytes)
        context.currentPosition += 4 + length
        
        return String(bytes, Charsets.UTF_8)
    }
    
    /**
     * 释放ByteBuffer回池
     */
    fun release(buffer: ByteBuffer) {
        bufferPool.release(buffer)
    }
    
    /**
     * 类型处理器接口
     */
    interface TypeHandler<T> {
        fun write(context: SerializationContext, value: T)
        fun read(context: DeserializationContext): T
    }
    
    /**
     * 支持增量序列化的类型处理器接口
     */
    interface DeltaTypeHandler<T> : TypeHandler<T> {
        fun writeDelta(context: SerializationContext, value: T, baseline: T)
        fun readDelta(context: DeserializationContext, baseline: T): T
    }
    
    /**
     * 字符串类型处理器
     */
    class StringTypeHandler : TypeHandler<String> {
        override fun write(context: SerializationContext, value: String) {
            // 检查引用
            if (context.objectReferences.containsKey(value)) {
                context.buffer.putInt(-context.objectReferences[value]!!)
                return
            }
            
            // 存储引用
            context.objectReferences[value] = context.currentPosition
            
            // 写入字符串长度
            val bytes = value.toByteArray(Charsets.UTF_8)
            context.buffer.putInt(bytes.size)
            
            // 写入字符串内容
            context.buffer.put(bytes)
            context.currentPosition += 4 + bytes.size
        }
        
        override fun read(context: DeserializationContext): String {
            // 检查引用
            val length = context.buffer.getInt()
            if (length < 0) {
                return context.objectReferences[-length] as String
            }
            
            // 读取字符串内容
            val bytes = ByteArray(length)
            context.buffer.get(bytes)
            val value = String(bytes, Charsets.UTF_8)
            
            // 存储引用
            context.objectReferences[context.currentPosition] = value
            context.currentPosition += 4 + length
            
            return value
        }
    }
    
    /**
     * 整数类型处理器
     */
    class IntTypeHandler : TypeHandler<Int> {
        override fun write(context: SerializationContext, value: Int) {
            context.buffer.putInt(value)
            context.currentPosition += 4
        }
        
        override fun read(context: DeserializationContext): Int {
            val value = context.buffer.getInt()
            context.currentPosition += 4
            return value
        }
    }
    
    /**
     * 长整数类型处理器
     */
    class LongTypeHandler : TypeHandler<Long> {
        override fun write(context: SerializationContext, value: Long) {
            context.buffer.putLong(value)
            context.currentPosition += 8
        }
        
        override fun read(context: DeserializationContext): Long {
            val value = context.buffer.getLong()
            context.currentPosition += 8
            return value
        }
    }
    
    /**
     * 双精度浮点数类型处理器
     */
    class DoubleTypeHandler : TypeHandler<Double> {
        override fun write(context: SerializationContext, value: Double) {
            context.buffer.putDouble(value)
            context.currentPosition += 8
        }
        
        override fun read(context: DeserializationContext): Double {
            val value = context.buffer.getDouble()
            context.currentPosition += 8
            return value
        }
    }
    
    /**
     * 布尔值类型处理器
     */
    class BooleanTypeHandler : TypeHandler<Boolean> {
        override fun write(context: SerializationContext, value: Boolean) {
            context.buffer.put(if (value) 1 else 0)
            context.currentPosition += 1
        }
        
        override fun read(context: DeserializationContext): Boolean {
            val value = context.buffer.get() == 1.toByte()
            context.currentPosition += 1
            return value
        }
    }
    
    /**
     * 数组类型处理器
     */
    class ArrayTypeHandler<T>(private val componentType: Class<*>) : TypeHandler<Array<T>> {
        private val elementHandler = when {
            componentType == Int::class.javaObjectType || componentType == Int::class.javaPrimitiveType -> 
                IntTypeHandler()
            componentType == Long::class.javaObjectType || componentType == Long::class.javaPrimitiveType -> 
                LongTypeHandler()
            componentType == Double::class.javaObjectType || componentType == Double::class.javaPrimitiveType -> 
                DoubleTypeHandler()
            componentType == Boolean::class.javaObjectType || componentType == Boolean::class.javaPrimitiveType -> 
                BooleanTypeHandler()
            componentType == String::class.java -> 
                StringTypeHandler()
            else -> 
                ObjectTypeHandler(componentType)
        }
        
        @Suppress("UNCHECKED_CAST")
        override fun write(context: SerializationContext, value: Array<T>) {
            // 写入数组长度
            context.buffer.putInt(value.size)
            context.currentPosition += 4
            
            // 写入元素
            for (element in value) {
                when (componentType) {
                    Int::class.javaObjectType, Int::class.javaPrimitiveType -> 
                        (elementHandler as IntTypeHandler).write(context, element as Int)
                    Long::class.javaObjectType, Long::class.javaPrimitiveType -> 
                        (elementHandler as LongTypeHandler).write(context, element as Long)
                    Double::class.javaObjectType, Double::class.javaPrimitiveType -> 
                        (elementHandler as DoubleTypeHandler).write(context, element as Double)
                    Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType -> 
                        (elementHandler as BooleanTypeHandler).write(context, element as Boolean)
                    String::class.java -> 
                        (elementHandler as StringTypeHandler).write(context, element as String)
                    else -> 
                        (elementHandler as ObjectTypeHandler<T>).write(context, element)
                }
            }
        }
        
        @Suppress("UNCHECKED_CAST")
        override fun read(context: DeserializationContext): Array<T> {
            // 读取数组长度
            val length = context.buffer.getInt()
            context.currentPosition += 4
            
            // 创建数组
            val array = java.lang.reflect.Array.newInstance(componentType, length) as Array<T>
            
            // 读取元素
            for (i in 0 until length) {
                val element = when (componentType) {
                    Int::class.javaObjectType, Int::class.javaPrimitiveType -> 
                        (elementHandler as IntTypeHandler).read(context)
                    Long::class.javaObjectType, Long::class.javaPrimitiveType -> 
                        (elementHandler as LongTypeHandler).read(context)
                    Double::class.javaObjectType, Double::class.javaPrimitiveType -> 
                        (elementHandler as DoubleTypeHandler).read(context)
                    Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType -> 
                        (elementHandler as BooleanTypeHandler).read(context)
                    String::class.java -> 
                        (elementHandler as StringTypeHandler).read(context)
                    else -> 
                        (elementHandler as ObjectTypeHandler<T>).read(context)
                }
                array[i] = element
            }
            
            return array
        }
    }
    
    /**
     * 列表类型处理器
     */
    class ListTypeHandler : TypeHandler<List<Any>> {
        override fun write(context: SerializationContext, value: List<Any>) {
            // 写入列表大小
            context.buffer.putInt(value.size)
            context.currentPosition += 4
            
            // 写入元素类型和值
            for (element in value) {
                val elementType = element.javaClass.name
                writeString(context, elementType)
                
                val handler = getHandler(element.javaClass)
                @Suppress("UNCHECKED_CAST")
                (handler as TypeHandler<Any>).write(context, element)
            }
        }
        
        private fun writeString(context: SerializationContext, value: String) {
            // 写入字符串长度
            val bytes = value.toByteArray(Charsets.UTF_8)
            context.buffer.putInt(bytes.size)
            
            // 写入字符串内容
            context.buffer.put(bytes)
            context.currentPosition += 4 + bytes.size
        }
        
        private fun readString(context: DeserializationContext): String {
            // 读取字符串长度
            val length = context.buffer.getInt()
            
            // 读取字符串内容
            val bytes = ByteArray(length)
            context.buffer.get(bytes)
            context.currentPosition += 4 + length
            
            return String(bytes, Charsets.UTF_8)
        }
        
        @Suppress("UNCHECKED_CAST")
        override fun read(context: DeserializationContext): List<Any> {
            // 读取列表大小
            val size = context.buffer.getInt()
            context.currentPosition += 4
            
            // 创建列表
            val list = ArrayList<Any>(size)
            
            // 读取元素
            for (i in 0 until size) {
                val elementType = readString(context)
                val type = Class.forName(elementType)
                
                val handler = getHandler(type)
                val element = handler.read(context)
                list.add(element)
            }
            
            return list
        }
        
        private fun getHandler(type: Class<*>): TypeHandler<*> {
            return when {
                type == String::class.java -> StringTypeHandler()
                type == Int::class.javaObjectType || type == Int::class.javaPrimitiveType -> IntTypeHandler()
                type == Long::class.javaObjectType || type == Long::class.javaPrimitiveType -> LongTypeHandler()
                type == Double::class.javaObjectType || type == Double::class.javaPrimitiveType -> DoubleTypeHandler()
                type == Boolean::class.javaObjectType || type == Boolean::class.javaPrimitiveType -> BooleanTypeHandler()
                type.isArray -> ArrayTypeHandler<Any>(type.componentType)
                List::class.java.isAssignableFrom(type) -> ListTypeHandler()
                Map::class.java.isAssignableFrom(type) -> MapTypeHandler()
                else -> ObjectTypeHandler<Any>(type)
            }
        }
    }
    
    /**
     * 映射类型处理器
     */
    class MapTypeHandler : TypeHandler<Map<Any, Any>> {
        override fun write(context: SerializationContext, value: Map<Any, Any>) {
            // 写入映射大小
            context.buffer.putInt(value.size)
            context.currentPosition += 4
            
            // 写入键值对
            for ((key, value) in value) {
                // 写入键类型和值
                val keyType = key.javaClass.name
                writeString(context, keyType)
                
                val keyHandler = getHandler(key.javaClass)
                @Suppress("UNCHECKED_CAST")
                (keyHandler as TypeHandler<Any>).write(context, key)
                
                // 写入值类型和值
                val valueType = value.javaClass.name
                writeString(context, valueType)
                
                val valueHandler = getHandler(value.javaClass)
                @Suppress("UNCHECKED_CAST")
                (valueHandler as TypeHandler<Any>).write(context, value)
            }
        }
        
        private fun writeString(context: SerializationContext, value: String) {
            // 写入字符串长度
            val bytes = value.toByteArray(Charsets.UTF_8)
            context.buffer.putInt(bytes.size)
            
            // 写入字符串内容
            context.buffer.put(bytes)
            context.currentPosition += 4 + bytes.size
        }
        
        private fun readString(context: DeserializationContext): String {
            // 读取字符串长度
            val length = context.buffer.getInt()
            
            // 读取字符串内容
            val bytes = ByteArray(length)
            context.buffer.get(bytes)
            context.currentPosition += 4 + length
            
            return String(bytes, Charsets.UTF_8)
        }
        
        @Suppress("UNCHECKED_CAST")
        override fun read(context: DeserializationContext): Map<Any, Any> {
            // 读取映射大小
            val size = context.buffer.getInt()
            context.currentPosition += 4
            
            // 创建映射
            val map = HashMap<Any, Any>(size)
            
            // 读取键值对
            for (i in 0 until size) {
                // 读取键类型和值
                val keyType = readString(context)
                val keyClass = Class.forName(keyType)
                
                val keyHandler = getHandler(keyClass)
                val key = keyHandler.read(context)
                
                // 读取值类型和值
                val valueType = readString(context)
                val valueClass = Class.forName(valueType)
                
                val valueHandler = getHandler(valueClass)
                val value = valueHandler.read(context)
                
                map[key] = value
            }
            
            return map
        }
        
        private fun getHandler(type: Class<*>): TypeHandler<*> {
            return when {
                type == String::class.java -> StringTypeHandler()
                type == Int::class.javaObjectType || type == Int::class.javaPrimitiveType -> IntTypeHandler()
                type == Long::class.javaObjectType || type == Long::class.javaPrimitiveType -> LongTypeHandler()
                type == Double::class.javaObjectType || type == Double::class.javaPrimitiveType -> DoubleTypeHandler()
                type == Boolean::class.javaObjectType || type == Boolean::class.javaPrimitiveType -> BooleanTypeHandler()
                type.isArray -> ArrayTypeHandler<Any>(type.componentType)
                List::class.java.isAssignableFrom(type) -> ListTypeHandler()
                Map::class.java.isAssignableFrom(type) -> MapTypeHandler()
                else -> ObjectTypeHandler<Any>(type)
            }
        }
    }
    
    /**
     * 对象类型处理器
     */
    class ObjectTypeHandler<T>(private val type: Class<*>) : DeltaTypeHandler<T> {
        // 缓存类型的字段
        private val fields = type.declaredFields.filter { !it.isSynthetic }.also {
            it.forEach { field -> field.isAccessible = true }
        }
        
        @Suppress("UNCHECKED_CAST")
        override fun write(context: SerializationContext, value: T) {
            // 检查引用
            if (context.objectReferences.containsKey(value as Any)) {
                context.buffer.putInt(-context.objectReferences[value]!!)
                return
            }
            
            // 存储引用
            context.objectReferences[value] = context.currentPosition
            
            // 写入每个字段
            for (field in fields) {
                val fieldValue = field.get(value)
                if (fieldValue != null) {
                    // 写入字段值标记（非空）
                    context.buffer.put(1)
                    context.currentPosition += 1
                    
                    // 写入字段值
                    val fieldHandler = getHandler(fieldValue.javaClass)
                    @Suppress("UNCHECKED_CAST")
                    (fieldHandler as TypeHandler<Any>).write(context, fieldValue)
                } else {
                    // 写入字段值标记（空）
                    context.buffer.put(0)
                    context.currentPosition += 1
                }
            }
        }
        
        @Suppress("UNCHECKED_CAST")
        override fun read(context: DeserializationContext): T {
            // 检查引用
            val pos = context.buffer.position()
            val header = context.buffer.getInt(pos)
            if (header < 0) {
                context.buffer.position(pos + 4)
                context.currentPosition += 4
                return context.objectReferences[-header] as T
            }
            
            // 恢复位置
            context.buffer.position(pos)
            
            // 创建实例
            val constructor = type.getDeclaredConstructor()
            constructor.isAccessible = true
            val instance = constructor.newInstance() as T
            
            // 存储引用
            context.objectReferences[context.currentPosition] = instance
            
            // 读取每个字段
            for (field in fields) {
                val isNull = context.buffer.get() == 0.toByte()
                context.currentPosition += 1
                
                if (!isNull) {
                    // 读取字段值
                    val fieldType = field.type
                    val fieldHandler = getHandler(fieldType)
                    val fieldValue = fieldHandler.read(context)
                    
                    // 设置字段值
                    field.set(instance, fieldValue)
                }
            }
            
            return instance
        }
        
        override fun writeDelta(context: SerializationContext, value: T, baseline: T) {
            // 检查引用
            if (context.objectReferences.containsKey(value as Any)) {
                context.buffer.putInt(-context.objectReferences[value]!!)
                return
            }
            
            // 存储引用
            context.objectReferences[value] = context.currentPosition
            
            // 写入已更改的字段
            var changedFields = 0
            val startPos = context.buffer.position()
            
            // 保留位置写入字段计数
            context.buffer.putShort(0)
            context.currentPosition += 2
            
            for (field in fields) {
                val fieldValue = field.get(value)
                val baselineValue = field.get(baseline)
                
                // 检查字段是否已更改
                if (isDifferent(fieldValue, baselineValue)) {
                    // 写入字段索引
                    val fieldIndex = fields.indexOf(field)
                    context.buffer.putShort(fieldIndex.toShort())
                    context.currentPosition += 2
                    
                    // 写入字段值
                    if (fieldValue != null) {
                        // 写入字段值标记（非空）
                        context.buffer.put(1)
                        context.currentPosition += 1
                        
                        // 写入字段值
                        val fieldHandler = getHandler(fieldValue.javaClass)
                        @Suppress("UNCHECKED_CAST")
                        (fieldHandler as TypeHandler<Any>).write(context, fieldValue)
                    } else {
                        // 写入字段值标记（空）
                        context.buffer.put(0)
                        context.currentPosition += 1
                    }
                    
                    changedFields++
                }
            }
            
            // 更新字段计数
            val currentPos = context.buffer.position()
            context.buffer.position(startPos)
            context.buffer.putShort(changedFields.toShort())
            context.buffer.position(currentPos)
        }
        
        @Suppress("UNCHECKED_CAST")
        override fun readDelta(context: DeserializationContext, baseline: T): T {
            // 检查引用
            val pos = context.buffer.position()
            val header = context.buffer.getInt(pos)
            if (header < 0) {
                context.buffer.position(pos + 4)
                context.currentPosition += 4
                return context.objectReferences[-header] as T
            }
            
            // 恢复位置
            context.buffer.position(pos)
            
            // 创建实例（从基准复制）
            val instance = baseline.javaClass.getDeclaredConstructor().newInstance() as T
            
            // 复制所有字段
            for (field in fields) {
                val value = field.get(baseline)
                field.set(instance, value)
            }
            
            // 存储引用
            context.objectReferences[context.currentPosition] = instance
            
            // 读取已更改字段数量
            val changedFields = context.buffer.getShort().toInt()
            context.currentPosition += 2
            
            // 读取已更改的字段
            for (i in 0 until changedFields) {
                // 读取字段索引
                val fieldIndex = context.buffer.getShort().toInt()
                context.currentPosition += 2
                
                val field = fields[fieldIndex]
                
                // 读取字段值
                val isNull = context.buffer.get() == 0.toByte()
                context.currentPosition += 1
                
                if (!isNull) {
                    // 读取字段值
                    val fieldType = field.type
                    val fieldHandler = getHandler(fieldType)
                    val fieldValue = fieldHandler.read(context)
                    
                    // 设置字段值
                    field.set(instance, fieldValue)
                } else {
                    field.set(instance, null)
                }
            }
            
            return instance
        }
        
        /**
         * 比较两个值是否不同
         */
        private fun isDifferent(value1: Any?, value2: Any?): Boolean {
            if (value1 == null && value2 == null) return false
            if (value1 == null || value2 == null) return true
            return value1 != value2
        }
        
        private fun getHandler(type: Class<*>): TypeHandler<*> {
            return when {
                type == String::class.java -> StringTypeHandler()
                type == Int::class.javaObjectType || type == Int::class.javaPrimitiveType -> IntTypeHandler()
                type == Long::class.javaObjectType || type == Long::class.javaPrimitiveType -> LongTypeHandler()
                type == Double::class.javaObjectType || type == Double::class.javaPrimitiveType -> DoubleTypeHandler()
                type == Boolean::class.javaObjectType || type == Boolean::class.javaPrimitiveType -> BooleanTypeHandler()
                type.isArray -> ArrayTypeHandler<Any>(type.componentType)
                List::class.java.isAssignableFrom(type) -> ListTypeHandler()
                Map::class.java.isAssignableFrom(type) -> MapTypeHandler()
                else -> ObjectTypeHandler<Any>(type)
            }
        }
    }
}

/**
 * ByteBuffer池，用于减少内存分配开销
 */
class BufferPool {
    private val buffers = ConcurrentHashMap<Int, MutableList<ByteBuffer>>()
    
    /**
     * 获取指定大小的ByteBuffer
     */
    fun acquire(size: Int): ByteBuffer {
        // 对齐到最接近的2的幂
        val alignedSize = nextPowerOfTwo(size)
        
        // 尝试从池中获取
        val availableBuffers = buffers[alignedSize]
        if (availableBuffers != null && availableBuffers.isNotEmpty()) {
            synchronized(availableBuffers) {
                if (availableBuffers.isNotEmpty()) {
                    val buffer = availableBuffers.removeAt(availableBuffers.size - 1)
                    buffer.clear()
                    return buffer
                }
            }
        }
        
        // 创建新的ByteBuffer
        return ByteBuffer.allocateDirect(alignedSize)
    }
    
    /**
     * 释放ByteBuffer回池
     */
    fun release(buffer: ByteBuffer) {
        val capacity = buffer.capacity()
        
        // 获取此容量的缓冲区列表
        val bufferList = buffers.computeIfAbsent(capacity) { ArrayList() }
        
        // 将缓冲区添加到列表
        synchronized(bufferList) {
            // 限制池大小以避免内存泄漏
            if (bufferList.size < 32) {
                buffer.clear()
                bufferList.add(buffer)
            }
        }
    }
    
    /**
     * 计算大于等于给定数的最小2的幂
     */
    private fun nextPowerOfTwo(n: Int): Int {
        var value = n
        value--
        value = value or (value shr 1)
        value = value or (value shr 2)
        value = value or (value shr 4)
        value = value or (value shr 8)
        value = value or (value shr 16)
        value++
        return value
    }
} 