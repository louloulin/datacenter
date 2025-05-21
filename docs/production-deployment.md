# 高频交易数据中心生产环境部署指南

本文档提供了将高频交易数据中心部署到生产环境的详细指南。

## 1. 环境要求

### 1.1 硬件推荐配置

- **处理器**: 至少16核心CPU，推荐Intel Xeon或AMD EPYC系列
- **内存**: 至少32GB RAM，推荐64GB或更高
- **存储**: 
  - 系统盘: SSD，至少100GB
  - 数据盘: NVMe SSD，至少500GB，用于高性能数据存储
- **网络**: 10Gbps或更高的网络接口，低延迟网络环境

### 1.2 软件要求

- **操作系统**: Linux (推荐Ubuntu 20.04 LTS或CentOS 8)
- **Java**: OpenJDK 11或更高版本
- **数据库**: PostgreSQL 13或更高版本，配置TimescaleDB扩展
- **监控**: Prometheus + Grafana
- **负载均衡**: NGINX或HAProxy (如果部署集群)

## 2. 集群部署架构

在生产环境中，推荐使用集群部署以提高可用性和性能。典型的部署架构如下：

```
                          [ 负载均衡器 ]
                                |
            +------------------+------------------+
            |                  |                  |
     [ API 节点 1 ]     [ API 节点 2 ]     [ API 节点 3 ]
            |                  |                  |
            +------------------+------------------+
                                |
                        [ 集群消息总线 ]
                                |
      +---------------+------------------+-----------------+
      |               |                  |                 |
[ 处理节点 1 ]   [ 处理节点 2 ]    [ 处理节点 3 ]   [ 处理节点 4 ]
      |               |                  |                 |
      +---------------+------------------+-----------------+
                                |
                        [ 共享存储/数据库 ]
```

## 3. 部署步骤

### 3.1 准备工作

1. 安装Java和所需依赖
```bash
# Ubuntu
sudo apt update
sudo apt install -y openjdk-11-jdk postgresql-client

# CentOS
sudo yum install -y java-11-openjdk postgresql
```

2. 创建服务用户
```bash
sudo useradd -r -s /bin/false -m -d /home/hftdc hftdc
```

3. 创建必要的目录结构
```bash
sudo mkdir -p /opt/hftdc /data/hftdc/journal /data/hftdc/snapshots /var/log/hftdc /etc/hftdc
sudo chown -R hftdc:hftdc /opt/hftdc /data/hftdc /var/log/hftdc /etc/hftdc
```

### 3.2 数据库设置

1. 安装PostgreSQL和TimescaleDB
```bash
# 参考TimescaleDB官方文档安装
# https://docs.timescale.com/latest/getting-started/installation
```

2. 创建数据库和用户
```bash
sudo -u postgres psql

CREATE DATABASE hftdc;
CREATE USER hftdc_prod WITH ENCRYPTED PASSWORD 'your_secure_password';
GRANT ALL PRIVILEGES ON DATABASE hftdc TO hftdc_prod;
\c hftdc
CREATE EXTENSION IF NOT EXISTS timescaledb;
```

### 3.3 应用程序部署

1. 构建应用程序
```bash
./gradlew clean build
```

2. 使用部署脚本进行部署
```bash
sudo scripts/deploy-production.sh
```

3. 配置系统服务
```bash
# 部署脚本已自动配置systemd服务
# 如需手动启动/停止:
sudo systemctl start hftdc
sudo systemctl stop hftdc
sudo systemctl status hftdc
```

### 3.4 监控设置

1. 安装Prometheus
```bash
# 安装Prometheus
# 参考官方文档: https://prometheus.io/docs/prometheus/latest/installation/
```

2. 配置Prometheus抓取高频交易系统指标
```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'hftdc'
    scrape_interval: 10s
    static_configs:
      - targets: ['localhost:9090']
```

3. 安装Grafana并导入仪表盘
```bash
# 安装Grafana
# 参考官方文档: https://grafana.com/docs/grafana/latest/installation/
```

## 4. 集群配置

### 4.1 Akka集群设置

在`application-production.conf`中，已经配置了集群基本设置。对于多节点部署，需要：

1. 每个节点的`akka.remote.artery.canonical.hostname`设置为节点实际IP
2. 各节点的`akka.cluster.seed-nodes`配置为相同的种子节点列表
3. 确保节点之间的防火墙允许Akka通信端口(默认2551)

### 4.2 负载均衡配置

使用NGINX配置示例：

```nginx
upstream hftdc_api {
    server node1:80;
    server node2:80;
    server node3:80;
}

server {
    listen 80;
    server_name api.hftdc.example.com;

    location / {
        proxy_pass http://hftdc_api;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

## 5. 性能优化

### 5.1 JVM优化

系统服务配置已包含基本的JVM优化参数：

```
-Xms4G -Xmx4G -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

对于高性能场景，可以考虑进一步优化：

1. 禁用偏向锁: `-XX:-UseBiasedLocking`
2. 预分配大对象堆空间: `-XX:G1HeapRegionSize=32M`
3. 调整垃圾收集器参数: `-XX:ConcGCThreads=2 -XX:ParallelGCThreads=4`

### 5.2 操作系统优化

1. 调整文件描述符限制
```bash
# /etc/security/limits.conf
hftdc soft nofile 65536
hftdc hard nofile 65536
```

2. 调整内核参数
```bash
# /etc/sysctl.conf
net.core.somaxconn = 4096
net.ipv4.tcp_max_syn_backlog = 4096
net.core.netdev_max_backlog = 4096
net.ipv4.tcp_fin_timeout = 30
net.ipv4.tcp_tw_reuse = 1
```

3. 如果使用NVMe存储，优化I/O调度器
```bash
echo "none" > /sys/block/nvme0n1/queue/scheduler
```

## 6. 安全配置

### 6.1 网络安全

1. 配置防火墙，仅允许必要的端口：
   - HTTP/HTTPS: 80/443
   - Akka集群: 2551
   - Prometheus: 9090
   - SSH: 22 (管理访问)

2. 设置SSL证书
```bash
# 生成自签名证书(仅用于测试)
sudo openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout /etc/hftdc/ssl/server.key \
  -out /etc/hftdc/ssl/server.crt
```

### 6.2 应用安全

1. 使用环境变量传递敏感配置(如数据库密码)
2. 确保系统用户权限最小化
3. 启用API密钥认证和IP白名单

## 7. 备份与恢复

### 7.1 定期备份

1. 日志和快照备份
```bash
# 示例备份脚本
#!/bin/bash
BACKUP_DATE=$(date +%Y%m%d%H%M)
BACKUP_DIR="/backup/hftdc/${BACKUP_DATE}"

mkdir -p ${BACKUP_DIR}
cp -r /data/hftdc/journal ${BACKUP_DIR}/journal
cp -r /data/hftdc/snapshots ${BACKUP_DIR}/snapshots
```

2. 数据库备份
```bash
pg_dump -U hftdc_prod -h localhost -d hftdc > /backup/hftdc/db/hftdc_${BACKUP_DATE}.sql
```

### 7.2 恢复流程

1. 从备份恢复日志和快照
```bash
cp -r /backup/hftdc/${BACKUP_DATE}/journal /data/hftdc/
cp -r /backup/hftdc/${BACKUP_DATE}/snapshots /data/hftdc/
chown -R hftdc:hftdc /data/hftdc
```

2. 从备份恢复数据库
```bash
psql -U hftdc_prod -h localhost -d hftdc < /backup/hftdc/db/hftdc_${BACKUP_DATE}.sql
```

3. 启动应用程序，系统会从最近的快照和日志中恢复状态
```bash
sudo systemctl start hftdc
```

## 8. 监控与维护

### 8.1 系统监控

配置Grafana仪表盘监控以下指标：

1. JVM指标：内存使用、GC活动、线程数
2. 业务指标：订单处理速率、延迟分布、交易量
3. 系统指标：CPU使用率、内存使用、磁盘IO、网络

### 8.2 日常维护

1. 日志轮转配置
```bash
# /etc/logrotate.d/hftdc
/var/log/hftdc/*.log {
    daily
    missingok
    rotate 14
    compress
    delaycompress
    notifempty
    create 0640 hftdc hftdc
}
```

2. 定期检查磁盘空间
```bash
# 添加到crontab
0 */4 * * * /usr/bin/df -h | grep "/data" | awk '{ if ($5 > "80%") system("echo \"Disk space alert on $(hostname): "$5" used\" | mail -s \"Disk Alert\" admin@example.com") }'
```

## 9. 故障排除

### 9.1 常见问题

1. **应用无法启动**
   - 检查日志: `journalctl -u hftdc.service -n 100`
   - 验证配置文件正确性
   - 确认数据库连接可用

2. **性能问题**
   - 检查GC日志: `/data/hftdc/logs/gc/gc.log`
   - 使用JMX连接分析: `jvisualvm` 或 `jconsole`
   - 检查系统资源使用情况: `top`, `iostat`, `vmstat`

3. **集群问题**
   - 检查Akka日志中的集群消息
   - 验证网络连接: `netstat -an | grep 2551`
   - 检查防火墙规则: `iptables -L`

### 9.2 联系支持

如需更多支持，请联系:

- 技术支持邮箱: support@hftdc.example.com
- 紧急联系电话: +1-888-123-4567 