#!/bin/bash
set -e

# 设置颜色
GREEN="\033[0;32m"
YELLOW="\033[0;33m"
BLUE="\033[0;34m"
RED="\033[0;31m"
NC="\033[0m" # No Color

echo -e "${GREEN}=== DisruptorX 性能基准测试 ===${NC}"

# 构建项目
echo -e "${YELLOW}构建项目...${NC}"
cd "$(dirname "$0")/.." || exit
../gradlew clean build

# 创建输出目录
mkdir -p benchmark-results

# 测试模式
if [ "$1" == "quick" ]; then
    # 快速测试模式
    echo -e "${YELLOW}执行快速测试模式...${NC}"
    
    echo -e "${BLUE}1. 单节点测试 (10k msg/s, 10秒)${NC}"
    java -Xmx1g -XX:+UseG1GC \
         -cp build/libs/disruptorx-*.jar \
         com.hftdc.disruptorx.benchmark.DisruptorXBenchmark \
         -d 10 -w 5 -r 10000 -n 1 -p 2 -c 2
         
elif [ "$1" == "single-node" ]; then
    # 单节点不同负载测试
    echo -e "${YELLOW}执行单节点扩展测试...${NC}"
    
    # 不同消息速率测试
    for RATE in 10000 50000 100000 200000 500000; do
        echo -e "${BLUE}单节点测试 ($RATE msg/s)${NC}"
        java -Xmx2g -XX:+UseG1GC \
             -cp build/libs/disruptorx-*.jar \
             com.hftdc.disruptorx.benchmark.DisruptorXBenchmark \
             -d 30 -w 10 -r $RATE -n 1 -p 4 -c 4
    done

elif [ "$1" == "multi-node" ]; then
    # 多节点测试
    echo -e "${YELLOW}执行多节点扩展测试...${NC}"
    
    # 不同节点数测试
    for NODES in 1 2 3; do
        echo -e "${BLUE}多节点测试 ($NODES 节点, 100000 msg/s)${NC}"
        java -Xmx2g -XX:+UseG1GC \
             -cp build/libs/disruptorx-*.jar \
             com.hftdc.disruptorx.benchmark.DisruptorXBenchmark \
             -d 30 -w 10 -r 100000 -n $NODES -p 4 -c 4
    done

elif [ "$1" == "message-size" ]; then
    # 消息大小测试
    echo -e "${YELLOW}执行消息大小扩展测试...${NC}"
    
    # 不同消息大小测试
    for SIZE in 128 1024 4096 16384; do
        echo -e "${BLUE}消息大小测试 ($SIZE 字节)${NC}"
        java -Xmx2g -XX:+UseG1GC \
             -cp build/libs/disruptorx-*.jar \
             com.hftdc.disruptorx.benchmark.DisruptorXBenchmark \
             -d 30 -w 10 -r 50000 -s $SIZE -n 1 -p 4 -c 4
    done

elif [ "$1" == "threads" ]; then
    # 线程数测试
    echo -e "${YELLOW}执行线程数扩展测试...${NC}"
    
    # 不同线程数测试
    for THREADS in 1 2 4 8 16; do
        echo -e "${BLUE}线程数测试 ($THREADS 生产者, $THREADS 消费者)${NC}"
        java -Xmx2g -XX:+UseG1GC \
             -cp build/libs/disruptorx-*.jar \
             com.hftdc.disruptorx.benchmark.DisruptorXBenchmark \
             -d 30 -w 10 -r 100000 -n 1 -p $THREADS -c $THREADS
    done

elif [ "$1" == "batch" ]; then
    # 批处理大小测试
    echo -e "${YELLOW}执行批处理大小扩展测试...${NC}"
    
    # 不同批处理大小测试
    for BATCH in 1 10 100 1000; do
        echo -e "${BLUE}批处理大小测试 (batch size: $BATCH)${NC}"
        java -Xmx2g -XX:+UseG1GC \
             -cp build/libs/disruptorx-*.jar \
             com.hftdc.disruptorx.benchmark.DisruptorXBenchmark \
             -d 30 -w 10 -r 100000 -n 1 -p 4 -c 4 -b $BATCH
    done

elif [ "$1" == "full" ]; then
    # 完整测试套件
    echo -e "${YELLOW}执行完整测试套件...${NC}"
    
    $0 single-node
    $0 multi-node
    $0 message-size
    $0 threads
    $0 batch
    
    echo -e "${GREEN}完整测试套件执行完毕!${NC}"

elif [ "$1" == "custom" ]; then
    # 自定义参数测试
    if [ -z "$2" ]; then
        echo -e "${RED}错误: 自定义模式需要提供额外参数${NC}"
        echo -e "示例: $0 custom \"-d 60 -r 200000 -n 3 -p 8 -c 8\""
        exit 1
    fi
    
    echo -e "${YELLOW}执行自定义测试...${NC}"
    echo -e "参数: $2"
    
    # shellcheck disable=SC2086
    java -Xmx2g -XX:+UseG1GC \
         -cp build/libs/disruptorx-*.jar \
         com.hftdc.disruptorx.benchmark.DisruptorXBenchmark \
         $2

else
    # 默认测试
    echo -e "${YELLOW}执行标准测试...${NC}"
    
    echo -e "${BLUE}1. 单节点基准测试 (50k msg/s, 30秒)${NC}"
    java -Xmx2g -XX:+UseG1GC \
         -cp build/libs/disruptorx-*.jar \
         com.hftdc.disruptorx.benchmark.DisruptorXBenchmark \
         -d 30 -w 10 -r 50000 -n 1 -p 4 -c 4
    
    echo -e "${BLUE}2. 多节点基准测试 (3节点, 50k msg/s, 30秒)${NC}"
    java -Xmx2g -XX:+UseG1GC \
         -cp build/libs/disruptorx-*.jar \
         com.hftdc.disruptorx.benchmark.DisruptorXBenchmark \
         -d 30 -w 10 -r 50000 -n 3 -p 4 -c 4
fi

echo -e "${GREEN}基准测试完成!${NC}"
echo -e "结果保存在 benchmark-results/ 目录下" 