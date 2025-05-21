#!/bin/bash
set -e

# 设置颜色
GREEN="\033[0;32m"
RED="\033[0;31m"
YELLOW="\033[0;33m"
NC="\033[0m" # No Color

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULT_DIR="benchmark-results/$TIMESTAMP"
mkdir -p "$RESULT_DIR"

echo -e "${GREEN}=== DisruptorX 性能测试 - $TIMESTAMP ===${NC}"
echo -e "结果将保存在 $RESULT_DIR"

# 构建Docker镜像
echo -e "${YELLOW}构建Docker镜像...${NC}"
docker build -t disruptorx-perf-test -f Dockerfile.test .

# 准备测试环境
echo -e "${YELLOW}准备测试环境...${NC}"
docker network create disruptorx-network 2>/dev/null || true

# 启动容器集群
echo -e "${YELLOW}启动测试集群...${NC}"
docker-compose up -d

# 等待容器启动
echo -e "${YELLOW}等待节点启动...${NC}"
sleep 15

# 执行性能测试
echo -e "${YELLOW}执行性能测试 - 100k msg/s${NC}"
docker exec disruptorx-node1 java -Xms1G -Xmx1G -XX:+UseG1GC \
  -cp "/app/libs/*:/app/classes" \
  -Dperformance.output.dir="/app/perf-results" \
  com.hftdc.disruptorx.performance.ProfilerTestRunner \
  100000 60

# 收集测试结果
echo -e "${YELLOW}收集测试结果...${NC}"
docker cp disruptorx-node1:/app/perf-results/ "$RESULT_DIR"

# 生成性能报告
echo -e "${YELLOW}生成性能报告...${NC}"
echo "=== DisruptorX 性能测试结果 - $TIMESTAMP ===" > "$RESULT_DIR/summary.txt"
echo "测试时间: $(date)" >> "$RESULT_DIR/summary.txt"
echo "" >> "$RESULT_DIR/summary.txt"

# 添加来自容器的性能日志
if [ -f "$RESULT_DIR/perf-results/profiler_benchmark.log" ]; then
  cat "$RESULT_DIR/perf-results/profiler_benchmark.log" >> "$RESULT_DIR/summary.txt"
else
  echo "未找到性能测试日志" >> "$RESULT_DIR/summary.txt"
fi

echo -e "${GREEN}性能测试完成！${NC}"
echo -e "结果保存在 $RESULT_DIR/summary.txt"

# 选择是否关闭测试环境
read -p "是否关闭测试环境? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
  echo -e "${YELLOW}关闭测试环境...${NC}"
  docker-compose down
  docker network rm disruptorx-network 2>/dev/null || true
else
  echo -e "${YELLOW}测试环境保持运行中.${NC}"
  echo -e "使用 'docker-compose down' 命令手动关闭."
fi 