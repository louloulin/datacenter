#!/bin/bash
set -e

# 设置颜色
GREEN="\033[0;32m"
RED="\033[0;31m"
YELLOW="\033[0;33m"
NC="\033[0m" # No Color

echo -e "${GREEN}=== DisruptorX 集成测试脚本 ===${NC}"

# 创建必要的目录
mkdir -p test-logs prometheus/rules grafana/dashboards grafana/provisioning

# 构建项目
echo -e "${YELLOW}构建项目...${NC}"
./gradlew clean build -x test

# 启动测试环境
echo -e "${YELLOW}启动Docker测试环境...${NC}"
docker-compose up -d

# 等待服务就绪
echo -e "${YELLOW}等待节点启动...${NC}"
sleep 20

# 执行集成测试用例
echo -e "${YELLOW}执行集成测试...${NC}"
./gradlew :disruptorx:integrationTest

# 检查测试结果
TEST_RESULT=$?
if [ $TEST_RESULT -eq 0 ]; then
  echo -e "${GREEN}✓ 所有测试通过!${NC}"
else
  echo -e "${RED}✗ 测试失败!${NC}"
fi

# 收集日志和指标
echo -e "${YELLOW}收集测试日志和指标...${NC}"
mkdir -p test-results/$(date +%Y%m%d_%H%M%S)
cp -r test-logs/* test-results/$(date +%Y%m%d_%H%M%S)/

# 选择是否关闭测试环境
read -p "是否关闭测试环境? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
  echo -e "${YELLOW}关闭测试环境...${NC}"
  docker-compose down
else
  echo -e "${YELLOW}测试环境保持运行中.${NC}"
  echo -e "使用 'docker-compose down' 命令手动关闭."
fi

# 输出测试结果摘要
echo -e "${GREEN}=== 测试摘要 ===${NC}"
echo -e "完成时间: $(date)"
echo -e "结果: $([ $TEST_RESULT -eq 0 ] && echo "${GREEN}通过${NC}" || echo "${RED}失败${NC}")"
echo -e "日志位置: test-results/$(date +%Y%m%d_%H%M%S)/"

# 退出码与测试结果一致
exit $TEST_RESULT 