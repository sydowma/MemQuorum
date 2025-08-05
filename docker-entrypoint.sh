#!/bin/bash

set -e

# 生成节点ID（如果未设置）
if [ -z "$NODE_ID" ]; then
    NODE_ID="node-$(hostname)-$(date +%s)"
    echo "Generated NODE_ID: $NODE_ID"
fi

# 设置JVM参数
JAVA_OPTS="${JAVA_OPTS} -Dserver.port=${SERVER_PORT}"
JAVA_OPTS="${JAVA_OPTS} -Dgrpc.port=${GRPC_PORT}"
JAVA_OPTS="${JAVA_OPTS} -Dnacos.server.addr=${NACOS_SERVER_ADDR}"
JAVA_OPTS="${JAVA_OPTS} -Dnode.id=${NODE_ID}"
JAVA_OPTS="${JAVA_OPTS} -Dnacos.logging.default.config.enabled=false"
JAVA_OPTS="${JAVA_OPTS} -Dnacos.logging.config="

# 如果设置了集群节点，添加到JVM参数
if [ -n "$CLUSTER_NODES" ]; then
    JAVA_OPTS="${JAVA_OPTS} -Dcluster.nodes=${CLUSTER_NODES}"
fi

# 输出配置信息
echo "========================================="
echo "MemQuorum Node Starting"
echo "========================================="
echo "NODE_ID: $NODE_ID"
echo "SERVER_PORT: $SERVER_PORT"
echo "GRPC_PORT: $GRPC_PORT"
echo "NACOS_SERVER_ADDR: $NACOS_SERVER_ADDR"
echo "CLUSTER_NODES: $CLUSTER_NODES"
echo "JAVA_OPTS: $JAVA_OPTS"
echo "========================================="

# 等待Nacos服务可用
echo "Waiting for Nacos server at $NACOS_SERVER_ADDR..."
while ! curl -s -f "http://$NACOS_SERVER_ADDR/nacos/v1/ns/instance/list?serviceName=healthcheck" >/dev/null 2>&1; do
    echo "Nacos server not ready, waiting..."
    sleep 5
done
echo "Nacos server is ready!"

# 启动应用
exec "$@" $JAVA_OPTS
