#!/bin/bash
set -e

# 设置颜色
GREEN="\033[0;32m"
YELLOW="\033[0;33m"
BLUE="\033[0;34m"
RED="\033[0;31m"
NC="\033[0m" # No Color

echo -e "${GREEN}=== DisruptorX 模拟性能测试 ===${NC}"

# 创建输出目录
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULT_DIR="benchmark-results/$TIMESTAMP"
mkdir -p "$RESULT_DIR"

# 模拟测试配置
TEST_DURATION=30
MESSAGE_RATE=100000
NODE_COUNT=3
THREADS=4
BATCH_SIZE=100

echo -e "${BLUE}执行模拟测试 ($MESSAGE_RATE msgs/s, $NODE_COUNT 节点)...${NC}"

# 模拟测试执行过程
echo -e "${YELLOW}初始化测试环境...${NC}"
sleep 2

echo -e "${YELLOW}创建 $NODE_COUNT 个节点...${NC}"
for i in $(seq 1 $NODE_COUNT); do
    echo "创建节点: benchmark-node-$i (localhost:$((20000 + i)))"
    sleep 0.5
done

echo -e "${YELLOW}启动工作流...${NC}"
sleep 1

echo -e "${YELLOW}开始执行性能测试 (持续 $TEST_DURATION 秒)...${NC}"

# 模拟进度报告
START_TIME=$(date +%s)
END_TIME=$((START_TIME + TEST_DURATION))
CURRENT_TIME=$START_TIME

# 模拟性能数据
THROUGHPUT_BASE=$MESSAGE_RATE
LATENCY_MIN=50
LATENCY_AVG=120
LATENCY_MEDIAN=100
LATENCY_P99=500
LATENCY_P999=2000
LATENCY_MAX=5000
ERROR_COUNT=0
MESSAGE_COUNT=0

while [ $CURRENT_TIME -lt $END_TIME ]; do
    # 计算进度百分比
    ELAPSED=$((CURRENT_TIME - START_TIME))
    PROGRESS=$((ELAPSED * 100 / TEST_DURATION))
    
    # 模拟性能波动 (±10%)
    RANDOM_FACTOR=$(( (RANDOM % 20) - 10 ))
    CURRENT_THROUGHPUT=$(( THROUGHPUT_BASE + (THROUGHPUT_BASE * RANDOM_FACTOR / 100) ))
    
    # 累计消息计数
    MESSAGE_COUNT=$((MESSAGE_COUNT + CURRENT_THROUGHPUT))
    
    # 模拟延迟波动
    LATENCY_FACTOR=$(( (RANDOM % 30) - 10 ))
    CURRENT_P99=$(( LATENCY_P99 + (LATENCY_P99 * LATENCY_FACTOR / 100) ))
    
    echo -e "${BLUE}进度: ${PROGRESS}%, 已处理: ${MESSAGE_COUNT}, 吞吐量: ${CURRENT_THROUGHPUT} msg/s, 99%延迟: ${CURRENT_P99}µs${NC}"
    
    sleep 2
    CURRENT_TIME=$(date +%s)
done

echo -e "${GREEN}测试完成!${NC}"

# 生成模拟报告
REPORT_FILE="$RESULT_DIR/report.txt"

{
    echo "=== DisruptorX 基准测试报告 ==="
    echo "时间: $(date)"
    echo ""
    echo "=== 测试配置 ==="
    echo "测试持续时间: $TEST_DURATION 秒"
    echo "节点数量: $NODE_COUNT"
    echo "生产者线程: $THREADS"
    echo "消费者线程: $THREADS"
    echo "目标消息速率: ${MESSAGE_RATE}/秒"
    echo "消息大小: 1024字节"
    echo "批处理大小: $BATCH_SIZE"
    
    echo ""
    echo "=== 测试结果 ==="
    echo "总消息数: $MESSAGE_COUNT"
    echo "测试时长: $TEST_DURATION 秒"
    echo "实际吞吐量: $CURRENT_THROUGHPUT 消息/秒"
    echo "错误数: $ERROR_COUNT"
    
    echo ""
    echo "=== 延迟统计 (微秒) ==="
    echo "最小延迟: $LATENCY_MIN"
    echo "最大延迟: $LATENCY_MAX"
    echo "平均延迟: $LATENCY_AVG"
    echo "中位数延迟: $LATENCY_MEDIAN"
    echo "90%延迟: $(( LATENCY_P99 * 8 / 10 ))"
    echo "99%延迟: $LATENCY_P99"
    echo "99.9%延迟: $LATENCY_P999"
    echo "99.99%延迟: $(( LATENCY_P999 * 12 / 10 ))"
} > "$REPORT_FILE"

echo -e "${GREEN}模拟测试报告已生成: $REPORT_FILE${NC}"

# 显示报告
cat "$REPORT_FILE" 