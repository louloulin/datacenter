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

# 运行基本测试
run_basic_tests() {
  echo "运行基本性能测试..."
  
  # 运行PerformanceOptimizationBenchmark
  echo "1. 运行性能优化基准测试..."
  java -Xms1G -Xmx1G -XX:+UseG1GC \
    -cp "build/libs/*:build/classes/kotlin/main:build/classes/kotlin/test" \
    org.junit.platform.console.ConsoleLauncher \
    --select-class=com.hftdc.disruptorx.performance.PerformanceOptimizationBenchmark \
    | tee "$RESULT_DIR/basic_benchmark.log"
    
  echo "基本性能测试完成。"
}

# 运行组件测试 - 测试每个优化组件的贡献
run_component_tests() {
  echo "运行组件性能测试..."
  
  # 运行ComponentBenchmarkTest
  echo "2. 运行组件优化基准测试..."
  java -Xms1G -Xmx1G -XX:+UseG1GC \
    -cp "build/libs/*:build/classes/kotlin/main:build/classes/kotlin/test" \
    org.junit.platform.console.ConsoleLauncher \
    --select-class=com.hftdc.disruptorx.performance.ComponentBenchmarkTest \
    | tee "$RESULT_DIR/component_benchmark.log"
    
  echo "组件性能测试完成。"
}

# 运行高级分析测试
run_profiler_tests() {
  echo "运行高级性能分析器测试..."
  
  # 以下测试需要创建一个特定的测试类来使用PerformanceProfiler
  echo "3. 运行带性能分析器的基准测试..."
  java -Xms1G -Xmx1G -XX:+UseG1GC \
    -cp "build/libs/*:build/classes/kotlin/main:build/classes/kotlin/test" \
    -Dperformance.output.dir="$RESULT_DIR" \
    com.hftdc.disruptorx.performance.ProfilerTestRunner \
    | tee "$RESULT_DIR/profiler_benchmark.log"
    
  echo "高级性能分析测试完成。"
}

# 可视化结果
visualize_results() {
  echo "可视化测试结果..."
  
  # 如果安装了Python和必要的依赖，运行可视化脚本
  if command -v python3 &> /dev/null && python3 -c "import matplotlib" &> /dev/null; then
    python3 scripts/visualize_performance.py
  else
    echo "警告: 未找到Python或matplotlib，跳过可视化步骤。"
    echo "请安装必要的依赖: pip install matplotlib numpy"
  fi
}

# 根据参数运行测试
case "$TEST_TYPE" in
  "basic")
    run_basic_tests
    ;;
  "component")
    run_component_tests
    ;;
  "profiler")
    run_profiler_tests
    ;;
  "all")
    run_basic_tests
    run_component_tests
    run_profiler_tests
    ;;
  *)
    echo "未知测试类型: $TEST_TYPE"
    echo "可用选项: basic, component, profiler, all"
    exit 1
    ;;
esac

# 生成摘要报告
echo "生成摘要报告..."
{
  echo "====== DisruptorX性能测试摘要 ======"
  echo "测试时间: $(date)"
  echo "测试类型: $TEST_TYPE"
  echo ""
  
  if [ -f "$RESULT_DIR/basic_benchmark.log" ]; then
    echo "=== 基本性能测试结果 ==="
    grep -A 20 "=== 测试完成 ===" "$RESULT_DIR/basic_benchmark.log" || echo "无性能数据"
    echo ""
  fi
  
  if [ -f "$RESULT_DIR/component_benchmark.log" ]; then
    echo "=== 组件性能测试结果 ==="
    grep -A 20 "=== .* 性能比较 ===" "$RESULT_DIR/component_benchmark.log" || echo "无性能数据"
    echo ""
  fi
  
  echo "详细日志保存在: $RESULT_DIR/"
} > "$RESULT_DIR/summary.txt"

echo "测试完成。结果摘要保存在 $RESULT_DIR/summary.txt"

# 可选：可视化结果
if [ "$2" == "--visualize" ]; then
  visualize_results
fi 