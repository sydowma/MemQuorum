#!/bin/bash

# Script to show all files mounted from current directory in docker-compose.yml

echo "=== Files mounted from current directory in docker-compose.yml ==="
echo

echo "📁 Configuration files being mounted:"
echo "├── ./config/prometheus.yml                     → /etc/prometheus/prometheus.yml"
echo "├── ./config/grafana/dashboards/                → /etc/grafana/provisioning/dashboards/"
echo "│   ├── dashboard.yml"
echo "│   └── memquorum-dashboard.json"
echo "└── ./config/grafana/datasources/               → /etc/grafana/provisioning/datasources/"
echo "    └── prometheus.yml"
echo

echo "💾 MemQuorum data and logs (mounted from current directory):"
echo "├── ./data/node1/        → /app/data (Node1 data)"
echo "├── ./logs/node1/        → /app/logs (Node1 logs)"
echo "├── ./data/node2/        → /app/data (Node2 data)"
echo "├── ./logs/node2/        → /app/logs (Node2 logs)"
echo "├── ./data/node3/        → /app/data (Node3 data)"
echo "└── ./logs/node3/        → /app/logs (Node3 logs)"
echo

echo "📊 Docker managed volumes:"
echo "├── prometheus-data      → /prometheus"
echo "└── grafana-data         → /var/lib/grafana"
echo

echo "🔍 To verify files and directories exist:"
echo "ls -la config/"
echo "ls -la config/grafana/dashboards/"
echo "ls -la config/grafana/datasources/"
echo "ls -la data/"
echo "ls -la logs/"
