#!/bin/bash

# 创建性能测试结果目录
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULT_DIR="performance-results/$TIMESTAMP"
mkdir -p "$RESULT_DIR"

echo "DisruptorX性能测试 - $TIMESTAMP"
echo "结果将保存在 $RESULT_DIR"

echo "检查CLASSPATH..."
COMPILED_CLASS=$(find . -name "ProfilerTestRunner.class" | head -1)
if [ -z "$COMPILED_CLASS" ]; then
  echo "编译后的类文件不存在，需要编译源代码..."
  if [ ! -f "./build.gradle.kts" ]; then
    echo "错误: 找不到build.gradle.kts文件"
    exit 1
  fi
  
  # 检查是否有Gradle Wrapper
  if [ -f "./gradlew" ]; then
    echo "使用Gradle Wrapper编译..."
    ./gradlew compileKotlin
  else
    echo "使用系统Gradle编译..."
    gradle compileKotlin
  fi
fi

echo "运行性能分析器测试..."
# 为测试使用更精确的classpath
java -Xms1G -Xmx1G -XX:+UseG1GC \
  -cp "./build/classes/kotlin/main:./build/resources/main:./build/libs/*" \
  -Dperformance.output.dir="$RESULT_DIR" \
  com.hftdc.disruptorx.performance.ProfilerTestRunner 2>&1 | tee "$RESULT_DIR/profiler_output.log"

RESULT=$?
if [ $RESULT -ne 0 ]; then
  echo "性能测试执行失败，查找更多信息..."
  find . -name "ProfilerTestRunner*"
  # 显示依赖项
  echo "项目依赖项:"
  if [ -f "./gradlew" ]; then
    ./gradlew dependencies || true
  else
    gradle dependencies || true
  fi
else
  echo "性能测试完成，结果保存在 $RESULT_DIR"
fi 