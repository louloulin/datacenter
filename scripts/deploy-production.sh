#!/bin/bash
# 高频交易系统生产环境部署脚本

set -e  # 发生错误时退出

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m' # 无颜色

# 配置变量
APP_NAME="hftdc"
APP_VERSION="1.0.0"
DEPLOY_DIR="/opt/hftdc"
DATA_DIR="/data/hftdc"
LOG_DIR="/var/log/hftdc"
CONFIG_DIR="/etc/hftdc"
SERVICE_USER="hftdc"
SERVICE_GROUP="hftdc"
CLUSTER_NODES=("node1" "node2" "node3")

# 打印消息函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

# 检查是否以root运行
if [ "$EUID" -ne 0 ]; then
    log_error "请以root用户运行此脚本"
fi

# 检查依赖
check_dependencies() {
    log_info "检查依赖项..."
    
    # 检查Java
    if ! command -v java &> /dev/null; then
        log_error "未找到Java，请安装Java 11或更高版本"
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    log_info "检测到Java版本: $JAVA_VERSION"
    
    # 检查PostgreSQL客户端
    if ! command -v psql &> /dev/null; then
        log_warn "未找到PostgreSQL客户端，某些功能可能不可用"
    fi
}

# 创建必要的目录
create_directories() {
    log_info "创建必要的目录结构..."
    
    mkdir -p $DEPLOY_DIR
    mkdir -p $DATA_DIR/journal
    mkdir -p $DATA_DIR/snapshots
    mkdir -p $DATA_DIR/logs/gc
    mkdir -p $LOG_DIR
    mkdir -p $CONFIG_DIR/ssl
    
    # 确保目录权限正确
    if getent passwd $SERVICE_USER > /dev/null 2>&1; then
        chown -R $SERVICE_USER:$SERVICE_GROUP $DEPLOY_DIR $DATA_DIR $LOG_DIR $CONFIG_DIR
    else
        log_warn "用户 $SERVICE_USER 不存在，跳过权限设置"
    fi
}

# 创建服务用户
create_service_user() {
    log_info "确保服务用户存在..."
    
    if ! getent passwd $SERVICE_USER > /dev/null 2>&1; then
        log_info "创建服务用户 $SERVICE_USER"
        useradd -r -s /bin/false -m -d /home/$SERVICE_USER $SERVICE_USER
    else
        log_info "服务用户 $SERVICE_USER 已存在"
    fi
}

# 部署应用
deploy_application() {
    log_info "部署应用程序..."
    
    # 假设应用已经构建并打包
    JAR_FILE="../build/libs/$APP_NAME-$APP_VERSION.jar"
    
    if [ ! -f "$JAR_FILE" ]; then
        log_error "找不到应用程序JAR文件: $JAR_FILE"
    fi
    
    cp "$JAR_FILE" "$DEPLOY_DIR/$APP_NAME.jar"
    log_info "应用程序复制到: $DEPLOY_DIR/$APP_NAME.jar"
    
    # 复制配置文件
    cp "../src/main/resources/application-production.conf" "$CONFIG_DIR/application.conf"
    log_info "配置文件复制到: $CONFIG_DIR/application.conf"
    
    # 创建系统服务文件
    create_systemd_service
}

# 创建系统服务
create_systemd_service() {
    log_info "创建系统服务..."
    
    cat > /etc/systemd/system/$APP_NAME.service << EOF
[Unit]
Description=High Frequency Trading Data Center
After=network.target postgresql.service

[Service]
User=$SERVICE_USER
Group=$SERVICE_GROUP
Type=simple
ExecStart=/usr/bin/java -Xms4G -Xmx4G \\
    -XX:+UseG1GC \\
    -XX:MaxGCPauseMillis=200 \\
    -Xlog:gc*:file=$DATA_DIR/logs/gc/gc.log:time,uptime,level,tags:filecount=10,filesize=100M \\
    -Dconfig.file=$CONFIG_DIR/application.conf \\
    -Denv=production \\
    -Dlog.dir=$LOG_DIR \\
    -jar $DEPLOY_DIR/$APP_NAME.jar
WorkingDirectory=$DEPLOY_DIR
Restart=always
RestartSec=5
LimitNOFILE=65536
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
EOF

    log_info "系统服务文件创建: /etc/systemd/system/$APP_NAME.service"
    
    # 重新加载systemd
    systemctl daemon-reload
}

# 配置防火墙
configure_firewall() {
    log_info "配置防火墙规则..."
    
    if command -v ufw &> /dev/null; then
        # 使用ufw
        ufw allow 80/tcp
        ufw allow 443/tcp
        ufw allow 9090/tcp  # Prometheus
        ufw allow 2551/tcp  # Akka Cluster
        log_info "已配置ufw防火墙规则"
    elif command -v firewall-cmd &> /dev/null; then
        # 使用firewalld
        firewall-cmd --permanent --add-port=80/tcp
        firewall-cmd --permanent --add-port=443/tcp
        firewall-cmd --permanent --add-port=9090/tcp
        firewall-cmd --permanent --add-port=2551/tcp
        firewall-cmd --reload
        log_info "已配置firewalld防火墙规则"
    else
        log_warn "未找到防火墙管理工具(ufw/firewalld)，请手动配置防火墙"
    fi
}

# 启动服务
start_service() {
    log_info "启动服务..."
    
    systemctl enable $APP_NAME.service
    systemctl start $APP_NAME.service
    
    sleep 5
    
    if systemctl is-active --quiet $APP_NAME.service; then
        log_info "服务已成功启动"
    else
        log_error "服务启动失败，请检查日志文件: $LOG_DIR/application.log"
    fi
}

# 显示完成信息
show_completion_info() {
    log_info "部署完成！"
    log_info "===================================="
    log_info "应用程序部署位置: $DEPLOY_DIR"
    log_info "配置文件位置: $CONFIG_DIR"
    log_info "数据目录: $DATA_DIR"
    log_info "日志目录: $LOG_DIR"
    log_info "===================================="
    log_info "系统服务: $APP_NAME.service"
    log_info "启动服务: systemctl start $APP_NAME.service"
    log_info "停止服务: systemctl stop $APP_NAME.service"
    log_info "查看状态: systemctl status $APP_NAME.service"
    log_info "查看日志: journalctl -u $APP_NAME.service"
    log_info "===================================="
}

# 执行部署过程
main() {
    log_info "开始部署 $APP_NAME 到生产环境..."
    
    check_dependencies
    create_service_user
    create_directories
    deploy_application
    configure_firewall
    start_service
    show_completion_info
}

# 执行主函数
main 