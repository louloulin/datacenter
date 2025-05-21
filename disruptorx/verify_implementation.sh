#!/bin/bash
set -e

# 设置颜色
GREEN="\033[0;32m"
RED="\033[0;31m"
YELLOW="\033[0;33m"
NC="\033[0m" # No Color

echo -e "${GREEN}=== DisruptorX 实现验证 ===${NC}"

# 验证文件是否存在
check_file() {
    local file=$1
    local description=$2
    
    if [ -f "$file" ]; then
        echo -e "${GREEN}✅ $description 已实现${NC}"
        return 0
    else
        echo -e "${RED}❌ $description 未实现${NC}"
        return 1
    fi
}

# 检查主要组件实现
echo -e "\n${YELLOW}检查核心优化组件实现:${NC}"
check_file "src/main/kotlin/com/hftdc/disruptorx/serialization/ZeroCopySerializer.kt" "零拷贝序列化器"
check_file "src/main/kotlin/com/hftdc/disruptorx/network/OptimizedNetworkTransport.kt" "优化网络传输层"

# 检查测试实现
echo -e "\n${YELLOW}检查测试实现:${NC}"
check_file "src/test/kotlin/com/hftdc/disruptorx/serialization/ZeroCopySerializerTest.kt" "序列化器测试"
check_file "src/test/kotlin/com/hftdc/disruptorx/network/OptimizedNetworkTransportTest.kt" "网络传输测试"

# 统计已实现功能
echo -e "\n${YELLOW}统计文档中实现功能标记:${NC}"
# 正则表达式匹配不同形式的标记
# 任务总数 = [x] + [✅] + [ ] 的数量
x_count=$(grep -o -E '\[x\]' ../disruptorx.md | wc -l)
checkmark_count=$(grep -o -E '\[✅\]' ../disruptorx.md | wc -l)
empty_count=$(grep -o -E '\[ \]' ../disruptorx.md | wc -l)

implementation_count=$((x_count + checkmark_count + empty_count))
completed_count=$checkmark_count
percentage=$(echo "scale=2; $completed_count / $implementation_count * 100" | bc)

echo -e "总任务数: ${implementation_count}"
echo -e "已完成任务数: ${completed_count}"
echo -e "完成度: ${percentage}%"

echo -e "\n${GREEN}验证完成!${NC}" 