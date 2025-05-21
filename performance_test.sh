#!/bin/bash

# DisruptorX性能测试脚本
# 用于运行各种性能测试并对比结果

set -e

# 创建性能测试结果目录
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULT_DIR="performance-results/$TIMESTAMP"
mkdir -p "$RESULT_DIR"

echo "DisruptorX性能测试 - $TIMESTAMP"
echo "结果将保存在 $RESULT_DIR"

# 定义测试类型
TEST_TYPE=${1:-"all"}
echo "测试类型: $TEST_TYPE"

# 编译项目
echo "编译项目..."
./gradlew clean build -x test

# 运行分析器测试 - 这是我们最关注的测试
run_profiler_tests() {
  echo "运行高级性能分析器测试..."
  
  # 以下测试需要创建一个特定的测试类来使用PerformanceProfiler
  echo "运行带性能分析器的基准测试..."
  java -Xms1G -Xmx1G -XX:+UseG1GC \
    -cp "disruptorx/build/libs/*:disruptorx/build/classes/kotlin/main:disruptorx/build/classes/kotlin/test" \
    -Dperformance.output.dir="$RESULT_DIR" \
    com.hftdc.disruptorx.performance.ProfilerTestRunner \
    | tee "$RESULT_DIR/profiler_benchmark.log"
    
  echo "高级性能分析测试完成。"
}

# 执行分析器测试
run_profiler_tests

# 生成摘要报告
echo "生成摘要报告..."
{
  echo "====== DisruptorX性能测试摘要 ======"
  echo "测试时间: $(date)"
  echo "测试类型: $TEST_TYPE"
  echo ""
  
  if [ -f "$RESULT_DIR/profiler_benchmark.log" ]; then
    echo "=== 性能分析器测试结果 ==="
    cat "$RESULT_DIR/profiler_benchmark.log"
    echo ""
  fi
  
  echo "详细日志保存在: $RESULT_DIR/"
} > "$RESULT_DIR/summary.txt"

echo "测试完成。结果摘要保存在 $RESULT_DIR/summary.txt" 