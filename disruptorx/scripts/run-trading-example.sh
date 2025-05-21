#!/bin/bash
set -e

# 设置颜色
GREEN="\033[0;32m"
YELLOW="\033[0;33m"
NC="\033[0m" # No Color

echo -e "${GREEN}=== DisruptorX 高频交易示例 ===${NC}"

# 构建项目
echo -e "${YELLOW}构建项目...${NC}"
cd "$(dirname "$0")/.." || exit
./gradlew clean build

# 运行高频交易示例
echo -e "${YELLOW}启动高频交易示例...${NC}"
java -Xmx512m -XX:+UseG1GC \
     -Dlog4j.configurationFile=file:scripts/log4j2-trading.xml \
     -cp build/libs/disruptorx-*.jar \
     com.hftdc.disruptorx.example.trading.workflow.TradingWorkflow

echo -e "${GREEN}示例执行完毕!${NC}" 