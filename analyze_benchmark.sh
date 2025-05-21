#!/bin/bash
set -e

# 设置颜色
GREEN="\033[0;32m"
RED="\033[0;31m"
YELLOW="\033[0;33m"
NC="\033[0m" # No Color

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULT_DIR="benchmark-analysis/$TIMESTAMP"
mkdir -p "$RESULT_DIR"

echo -e "${GREEN}=== DisruptorX 性能分析 - $TIMESTAMP ===${NC}"

# 基础分析函数
analyze_performance() {
  local benchmark_dir=$1
  local result_file="$RESULT_DIR/$(basename $benchmark_dir)_analysis.txt"
  
  echo "分析 $benchmark_dir..."
  echo "=== DisruptorX 性能分析报告 ===" > $result_file
  echo "生成时间: $(date)" >> $result_file
  echo "分析目标: $benchmark_dir" >> $result_file
  echo "" >> $result_file
  
  # 提取性能数据
  if [ -f "$benchmark_dir/report.txt" ]; then
    echo "=== 基本性能指标 ===" >> $result_file
    grep -A 15 "性能结果" "$benchmark_dir/report.txt" >> $result_file 2>/dev/null || true
    grep -A 10 "延迟统计" "$benchmark_dir/report.txt" >> $result_file 2>/dev/null || true
    echo "" >> $result_file
  fi
  
  # 检查CSV数据
  local csv_file=$(find $benchmark_dir -name "performance_data.csv" | head -1)
  if [ -n "$csv_file" ]; then
    echo "=== 吞吐量和延迟统计 ===" >> $result_file
    echo "采样数: $(grep -v timestamp $csv_file | wc -l)" >> $result_file
    echo "平均吞吐量: $(grep -v timestamp $csv_file | awk -F, '{sum+=$2} END {print sum/NR}') 消息/秒" >> $result_file
    echo "最高吞吐量: $(grep -v timestamp $csv_file | awk -F, '{if(max<$2)max=$2} END {print max}') 消息/秒" >> $result_file
    echo "最低吞吐量: $(grep -v timestamp $csv_file | awk -F, '{if(NR==1){min=$2}else{if($2<min)min=$2}} END {print min}') 消息/秒" >> $result_file
    echo "平均中位数延迟: $(grep -v timestamp $csv_file | awk -F, '{sum+=$3} END {print sum/NR}') 微秒" >> $result_file
    echo "平均99%延迟: $(grep -v timestamp $csv_file | awk -F, '{sum+=$4} END {print sum/NR}') 微秒" >> $result_file
    echo "平均99.9%延迟: $(grep -v timestamp $csv_file | awk -F, '{sum+=$5} END {print sum/NR}') 微秒" >> $result_file
    echo "" >> $result_file
  fi
  
  # 汇总
  echo "分析完成，结果保存在 $result_file"
}

# 查找所有基准测试结果目录
find benchmark-results -maxdepth 2 -type d -name "*2025*" | while read dir; do
  analyze_performance $dir
done

# 生成总体分析报告
echo -e "${YELLOW}生成总体分析报告...${NC}"
{
  echo "=== DisruptorX 总体性能分析 ==="
  echo "分析时间: $(date)"
  echo ""
  echo "=== 测试数据集 ==="
  find benchmark-results -maxdepth 2 -type d -name "*2025*" | wc -l | awk '{print "测试总数: " $1}'
  echo ""
  
  # 比较不同测试的性能
  echo "=== 性能比较 ==="
  echo "| 测试日期 | 平均吞吐量 | 中位数延迟 | 99%延迟 | 99.9%延迟 |"
  echo "|----------|------------|------------|---------|-----------|"
  
  find benchmark-results -maxdepth 2 -type d -name "*2025*" | sort | while read dir; do
    local date=$(basename $dir)
    local csv_file=$(find $dir -name "performance_data.csv" | head -1)
    if [ -n "$csv_file" ]; then
      local throughput=$(grep -v timestamp $csv_file | awk -F, '{sum+=$2} END {print sum/NR}')
      local p50=$(grep -v timestamp $csv_file | awk -F, '{sum+=$3} END {print sum/NR}')
      local p99=$(grep -v timestamp $csv_file | awk -F, '{sum+=$4} END {print sum/NR}')
      local p999=$(grep -v timestamp $csv_file | awk -F, '{sum+=$5} END {print sum/NR}')
      printf "| %s | %.0f | %.1f | %.1f | %.1f |\n" "$date" "$throughput" "$p50" "$p99" "$p999"
    fi
  done
} > "$RESULT_DIR/overall_analysis.txt"

echo -e "${GREEN}性能分析完成！${NC}"
echo -e "结果保存在 $RESULT_DIR/"
echo -e "总体分析: $RESULT_DIR/overall_analysis.txt" 