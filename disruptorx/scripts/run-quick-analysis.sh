#!/bin/bash
set -e

# 设置颜色
GREEN="\033[0;32m"
YELLOW="\033[0;33m"
BLUE="\033[0;34m"
RED="\033[0;31m"
NC="\033[0m" # No Color

echo -e "${GREEN}=== DisruptorX 快速性能分析 ===${NC}"

# 检查依赖
if ! command -v gnuplot &> /dev/null; then
    echo -e "${RED}警告: 未找到gnuplot，将不会生成图表${NC}"
    GNUPLOT_AVAILABLE=false
else
    GNUPLOT_AVAILABLE=true
fi

# 构建项目
echo -e "${YELLOW}构建项目...${NC}"
cd "$(dirname "$0")/.." || exit
# 使用项目根目录的gradlew
../gradlew clean build

# 创建输出目录
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULT_DIR="benchmark-results/quick-analysis-$TIMESTAMP"
mkdir -p "$RESULT_DIR"

# 运行单节点测试
echo -e "${BLUE}运行单节点测试 (10k, 50k, 100k msg/s)...${NC}"

# 测试不同的消息速率
for RATE in 10000 50000 100000; do
    echo -e "${YELLOW}测试 $RATE 消息/秒...${NC}"
    
    java -Xmx1g -XX:+UseG1GC \
         -cp build/libs/disruptorx-*.jar \
         com.hftdc.disruptorx.benchmark.DisruptorXBenchmark \
         -d 20 -w 5 -r "$RATE" -n 1 -p 4 -c 4
    
    # 找到最新的结果目录
    LATEST_DIR=$(find benchmark-results -maxdepth 1 -type d -name "2*" | sort | tail -n 1)
    
    # 如果找到结果目录，复制和重命名
    if [ -d "$LATEST_DIR" ]; then
        RATE_DIR="$RESULT_DIR/rate-$RATE"
        mkdir -p "$RATE_DIR"
        cp "$LATEST_DIR"/* "$RATE_DIR/"
        echo "结果已复制到 $RATE_DIR"
    else
        echo -e "${RED}无法找到测试结果目录${NC}"
    fi
done

# 生成分析报告
echo -e "${BLUE}生成分析报告...${NC}"
REPORT_FILE="$RESULT_DIR/analysis-report.txt"

{
    echo "=== DisruptorX 性能分析报告 ==="
    echo "生成时间: $(date)"
    echo ""
    echo "=== 吞吐量比较 ==="
    
    # 提取和比较吞吐量
    echo "| 消息速率 | 实际吞吐量 | 最大延迟 (us) | 99%延迟 (us) | 99.9%延迟 (us) |"
    echo "|----------|------------|--------------|--------------|----------------|"
    
    for RATE in 10000 50000 100000; do
        RATE_REPORT="$RESULT_DIR/rate-$RATE/report.txt"
        if [ -f "$RATE_REPORT" ]; then
            THROUGHPUT=$(grep "实际吞吐量" "$RATE_REPORT" | awk '{print $2}')
            MAX_LATENCY=$(grep "最大延迟" "$RATE_REPORT" | awk '{print $2}')
            P99_LATENCY=$(grep "99%延迟" "$RATE_REPORT" | awk '{print $2}')
            P999_LATENCY=$(grep "99.9%延迟" "$RATE_REPORT" | awk '{print $2}')
            
            echo "| $RATE | $THROUGHPUT | $MAX_LATENCY | $P99_LATENCY | $P999_LATENCY |"
        else
            echo "| $RATE | 数据不可用 | 数据不可用 | 数据不可用 | 数据不可用 |"
        fi
    done
    
    echo ""
    echo "=== 结论 ==="
    
    # 比较实际吞吐量与目标速率
    for RATE in 10000 50000 100000; do
        RATE_REPORT="$RESULT_DIR/rate-$RATE/report.txt"
        if [ -f "$RATE_REPORT" ]; then
            THROUGHPUT=$(grep "实际吞吐量" "$RATE_REPORT" | awk '{print $2}' | sed 's/,//g')
            THROUGHPUT_NUM=$(echo "$THROUGHPUT" | sed 's/,//g')
            
            if (( $(echo "$THROUGHPUT_NUM >= $RATE" | bc -l) )); then
                echo "* $RATE 消息/秒: 达到目标速率 (实际: $THROUGHPUT)"
            else
                PERCENT=$(echo "scale=1; $THROUGHPUT_NUM * 100 / $RATE" | bc)
                echo "* $RATE 消息/秒: 未达到目标速率 (实际: $THROUGHPUT, 达成率: $PERCENT%)"
            fi
        fi
    done
    
    echo ""
    echo "=== 性能特征 ==="
    
    # 评估性能特征
    HIGHEST_RATE_REPORT="$RESULT_DIR/rate-100000/report.txt"
    if [ -f "$HIGHEST_RATE_REPORT" ]; then
        AVG_LATENCY=$(grep "平均延迟" "$HIGHEST_RATE_REPORT" | awk '{print $3}')
        MEDIAN_LATENCY=$(grep "中位数延迟" "$HIGHEST_RATE_REPORT" | awk '{print $2}')
        P999_LATENCY=$(grep "99.9%延迟" "$HIGHEST_RATE_REPORT" | awk '{print $2}')
        
        echo "* 在高负载下 (100k msgs/s):"
        echo "  - 平均延迟: $AVG_LATENCY 微秒"
        echo "  - 中位数延迟: $MEDIAN_LATENCY 微秒"
        echo "  - 99.9%延迟: $P999_LATENCY 微秒"
        
        # 延迟分析
        MEDIAN_NUM=$(echo "$MEDIAN_LATENCY" | sed 's/,//g')
        P999_NUM=$(echo "$P999_LATENCY" | sed 's/,//g')
        
        if (( $(echo "$MEDIAN_NUM < 1000" | bc -l) )); then
            echo "* 延迟特性: 优秀 (中位数延迟 < 1ms)"
        elif (( $(echo "$MEDIAN_NUM < 5000" | bc -l) )); then
            echo "* 延迟特性: 良好 (中位数延迟 < 5ms)"
        else
            echo "* 延迟特性: 一般 (中位数延迟 > 5ms)"
        fi
        
        P999_MEDIAN_RATIO=$(echo "scale=1; $P999_NUM / $MEDIAN_NUM" | bc)
        if (( $(echo "$P999_MEDIAN_RATIO < 10" | bc -l) )); then
            echo "* 延迟一致性: 优秀 (99.9%延迟 / 中位数延迟 < 10x)"
        elif (( $(echo "$P999_MEDIAN_RATIO < 50" | bc -l) )); then
            echo "* 延迟一致性: 良好 (99.9%延迟 / 中位数延迟 < 50x)"
        else
            echo "* 延迟一致性: 一般 (99.9%延迟 / 中位数延迟 > 50x)"
        fi
    fi
    
    echo ""
    echo "=== 优化建议 ==="
    
    # 根据测试结果提供优化建议
    HIGHEST_RATE_REPORT="$RESULT_DIR/rate-100000/report.txt"
    if [ -f "$HIGHEST_RATE_REPORT" ]; then
        THROUGHPUT=$(grep "实际吞吐量" "$HIGHEST_RATE_REPORT" | awk '{print $2}')
        THROUGHPUT_NUM=$(echo "$THROUGHPUT" | sed 's/,//g')
        
        if (( $(echo "$THROUGHPUT_NUM < 100000" | bc -l) )); then
            echo "* 吞吐量未达到目标，建议:"
            echo "  - 增加生产者和消费者线程数"
            echo "  - 增加批处理大小"
            echo "  - 检查系统资源使用情况，可能需要更多CPU或内存"
        fi
        
        P999_LATENCY=$(grep "99.9%延迟" "$HIGHEST_RATE_REPORT" | awk '{print $2}')
        P999_NUM=$(echo "$P999_LATENCY" | sed 's/,//g')
        
        if (( $(echo "$P999_NUM > 10000" | bc -l) )); then
            echo "* 高尾部延迟，建议:"
            echo "  - 调整GC参数减少暂停时间"
            echo "  - 考虑使用更大的RingBuffer大小"
            echo "  - 监控并减少系统的其他干扰"
        fi
    fi
    
} > "$REPORT_FILE"

# 如果gnuplot可用，生成图表
if [ "$GNUPLOT_AVAILABLE" = true ]; then
    echo -e "${BLUE}生成性能图表...${NC}"
    
    # 准备图表数据
    DATA_FILE="$RESULT_DIR/throughput_data.txt"
    echo "# Rate ActualThroughput MedianLatency P99Latency" > "$DATA_FILE"
    
    for RATE in 10000 50000 100000; do
        RATE_REPORT="$RESULT_DIR/rate-$RATE/report.txt"
        if [ -f "$RATE_REPORT" ]; then
            THROUGHPUT=$(grep "实际吞吐量" "$RATE_REPORT" | awk '{print $2}' | sed 's/,//g')
            MEDIAN=$(grep "中位数延迟" "$RATE_REPORT" | awk '{print $2}' | sed 's/,//g')
            P99=$(grep "99%延迟" "$RATE_REPORT" | awk '{print $2}' | sed 's/,//g')
            
            echo "$RATE $THROUGHPUT $MEDIAN $P99" >> "$DATA_FILE"
        fi
    done
    
    # 生成吞吐量图表
    THROUGHPUT_PLOT="$RESULT_DIR/throughput.png"
    gnuplot << EOF
    set terminal png size 800,600
    set output "$THROUGHPUT_PLOT"
    set title "DisruptorX性能 - 目标vs实际吞吐量"
    set xlabel "目标速率 (msg/s)"
    set ylabel "实际吞吐量 (msg/s)"
    set grid
    set key top left
    set style data linespoints
    set pointsize 2
    plot "$DATA_FILE" using 1:2 title "实际吞吐量" with linespoints lw 2, \
         x title "理想线" with lines lt 2 lw 1
EOF
    
    # 生成延迟图表
    LATENCY_PLOT="$RESULT_DIR/latency.png"
    gnuplot << EOF
    set terminal png size 800,600
    set output "$LATENCY_PLOT"
    set title "DisruptorX性能 - 延迟随吞吐量变化"
    set xlabel "吞吐量 (msg/s)"
    set ylabel "延迟 (微秒)"
    set grid
    set key top left
    set style data linespoints
    set pointsize 2
    set logscale y
    plot "$DATA_FILE" using 2:3 title "中位数延迟" with linespoints lw 2, \
         "$DATA_FILE" using 2:4 title "99%延迟" with linespoints lw 2
EOF
    
    echo -e "${GREEN}图表已生成: $THROUGHPUT_PLOT 和 $LATENCY_PLOT${NC}"
fi

echo -e "${GREEN}性能分析完成!${NC}"
echo -e "分析报告: $REPORT_FILE"

# 打开分析报告
if command -v less &> /dev/null; then
    less "$REPORT_FILE"
else
    cat "$REPORT_FILE"
fi 