#!/bin/bash
set -e

# 检查目录是否存在
mkdir -p /app/logs

# 打印启动信息
echo "Starting DisruptorX node..."
echo "Node ID: $NODE_ID"
echo "Node Role: $NODE_ROLE"
echo "Seed Nodes: $SEED_NODES"
echo "Host: $HOST"
echo "Port: $PORT"
echo "Leader Candidate: $LEADER_CANDIDATE"

# 设置JVM参数
JVM_ARGS="$JVM_OPTS -Dlogback.configurationFile=file:/app/logback.xml"

# 等待网络就绪
echo "Waiting for network to be ready..."
SEED_NODE_LIST=(${SEED_NODES//,/ })
for seed in "${SEED_NODE_LIST[@]}"; do
  host=$(echo $seed | cut -d':' -f1)
  port=$(echo $seed | cut -d':' -f2)
  if [ "$host" != "$NODE_ID" ]; then
    echo "Waiting for $host:$port..."
    until nc -z $host $port || [ $SECONDS -gt 60 ]; do
      sleep 1
    done
  fi
done

# 启动应用
echo "Starting node with command: java $JVM_ARGS -jar /app/app.jar"
java $JVM_ARGS \
  -Dnode.id=$NODE_ID \
  -Dnode.role=$NODE_ROLE \
  -Dseed.nodes=$SEED_NODES \
  -Dnode.host=$HOST \
  -Dnode.port=$PORT \
  -Dnode.leader-candidate=$LEADER_CANDIDATE \
  -jar /app/app.jar 