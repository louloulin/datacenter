#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import glob
import csv
import re
import matplotlib.pyplot as plt
import numpy as np
from datetime import datetime
import argparse

def find_latest_benchmark():
    """查找最新的基准测试结果目录"""
    dirs = sorted(glob.glob('performance-results/*/'), reverse=True)
    if not dirs:
        print("未找到性能测试结果目录")
        sys.exit(1)
    return dirs[0]

def parse_benchmark_report(file_path):
    """解析基准测试报告文件"""
    data = {}
    with open(file_path, 'r') as f:
        content = f.read()
        
        # 提取基本信息
        data['name'] = re.search(r'====== 性能分析报告: (.*) ======', content).group(1)
        data['timestamp'] = re.search(r'时间: (.*)', content).group(1)
        data['event_count'] = int(re.search(r'总事件数: (\d+)', content).group(1))
        data['duration'] = float(re.search(r'持续时间: ([\d\.]+) 秒', content).group(1))
        data['throughput'] = int(re.search(r'吞吐量: (\d+) 事件/秒', content).group(1))
        
        # 提取延迟数据
        data['median_latency'] = float(re.search(r'中位数: ([\d\.]+)', content).group(1))
        data['p90_latency'] = float(re.search(r'90%: ([\d\.]+)', content).group(1))
        data['p99_latency'] = float(re.search(r'99%: ([\d\.]+)', content).group(1))
        data['p999_latency'] = float(re.search(r'99.9%: ([\d\.]+)', content).group(1))
        data['max_latency'] = float(re.search(r'最大: ([\d\.]+)', content).group(1))
        
        # 提取GC统计
        data['gc_pause'] = int(re.search(r'GC暂停总时间: (\d+) 毫秒', content).group(1))
        data['gc_percentage'] = float(re.search(r'GC暂停百分比: ([\d\.]+)%', content).group(1))
        
        # 提取资源使用
        data['cpu_utilization'] = int(re.search(r'CPU利用率: (\d+)%', content).group(1))
        data['memory_usage'] = int(re.search(r'内存使用峰值: (\d+) MB', content).group(1))
    
    return data

def parse_latency_csv(file_path):
    """解析延迟分布CSV文件"""
    percentiles = []
    latencies = []
    
    with open(file_path, 'r') as f:
        reader = csv.reader(f)
        next(reader)  # 跳过标题行
        for row in reader:
            percentiles.append(float(row[0]))
            latencies.append(float(row[1]))
    
    return percentiles, latencies

def compare_benchmarks(baseline_dir, optimized_dir):
    """比较两个基准测试结果"""
    # 查找报告文件
    baseline_report = glob.glob(os.path.join(baseline_dir, '*.txt'))[0]
    optimized_report = glob.glob(os.path.join(optimized_dir, '*.txt'))[0]
    
    # 解析报告
    baseline_data = parse_benchmark_report(baseline_report)
    optimized_data = parse_benchmark_report(optimized_report)
    
    # 查找延迟CSV文件
    baseline_csv = glob.glob(os.path.join(baseline_dir, '*_latencies.csv'))[0]
    optimized_csv = glob.glob(os.path.join(optimized_dir, '*_latencies.csv'))[0]
    
    # 解析延迟数据
    baseline_percentiles, baseline_latencies = parse_latency_csv(baseline_csv)
    optimized_percentiles, optimized_latencies = parse_latency_csv(optimized_csv)
    
    # 计算改进百分比
    throughput_improvement = (optimized_data['throughput'] - baseline_data['throughput']) * 100 / baseline_data['throughput']
    latency_improvement = (baseline_data['p99_latency'] - optimized_data['p99_latency']) * 100 / baseline_data['p99_latency']
    
    # 绘制对比图
    plt.figure(figsize=(15, 10))
    
    # 1. 吞吐量对比
    plt.subplot(2, 2, 1)
    labels = ['基线版本', '优化版本']
    throughputs = [baseline_data['throughput'], optimized_data['throughput']]
    plt.bar(labels, throughputs, color=['blue', 'green'])
    plt.title('吞吐量对比 (事件/秒)')
    plt.ylabel('事件/秒')
    plt.grid(axis='y', linestyle='--', alpha=0.7)
    
    # 在柱状图上添加数值标签
    for i, v in enumerate(throughputs):
        plt.text(i, v + 1000, f"{v:,}", ha='center')
    
    # 2. 延迟对比
    plt.subplot(2, 2, 2)
    metrics = ['中位数', '90%', '99%', '99.9%', '最大']
    baseline_values = [
        baseline_data['median_latency'],
        baseline_data['p90_latency'],
        baseline_data['p99_latency'],
        baseline_data['p999_latency'],
        min(baseline_data['max_latency'], 1000)  # 限制最大值以便更好的可视化
    ]
    optimized_values = [
        optimized_data['median_latency'],
        optimized_data['p90_latency'],
        optimized_data['p99_latency'],
        optimized_data['p999_latency'],
        min(optimized_data['max_latency'], 1000)  # 限制最大值以便更好的可视化
    ]
    
    x = np.arange(len(metrics))
    width = 0.35
    
    plt.bar(x - width/2, baseline_values, width, label='基线版本', color='blue')
    plt.bar(x + width/2, optimized_values, width, label='优化版本', color='green')
    
    plt.xlabel('延迟百分位')
    plt.ylabel('延迟 (微秒)')
    plt.title('延迟对比')
    plt.xticks(x, metrics)
    plt.legend()
    plt.grid(axis='y', linestyle='--', alpha=0.7)
    
    # 3. 延迟分布曲线
    plt.subplot(2, 2, 3)
    plt.plot(baseline_percentiles, baseline_latencies, label='基线版本', color='blue')
    plt.plot(optimized_percentiles, optimized_latencies, label='优化版本', color='green')
    plt.xlabel('百分位')
    plt.ylabel('延迟 (微秒)')
    plt.title('延迟分布曲线')
    plt.legend()
    plt.grid(linestyle='--', alpha=0.7)
    
    # 4. 资源使用对比
    plt.subplot(2, 2, 4)
    resource_metrics = ['CPU利用率 (%)', 'GC暂停 (ms)', '内存使用 (MB)']
    baseline_resources = [
        baseline_data['cpu_utilization'],
        baseline_data['gc_pause'],
        baseline_data['memory_usage']
    ]
    optimized_resources = [
        optimized_data['cpu_utilization'],
        optimized_data['gc_pause'],
        optimized_data['memory_usage']
    ]
    
    x = np.arange(len(resource_metrics))
    
    plt.bar(x - width/2, baseline_resources, width, label='基线版本', color='blue')
    plt.bar(x + width/2, optimized_resources, width, label='优化版本', color='green')
    
    plt.xlabel('资源指标')
    plt.ylabel('值')
    plt.title('资源使用对比')
    plt.xticks(x, resource_metrics)
    plt.legend()
    plt.grid(axis='y', linestyle='--', alpha=0.7)
    
    # 添加总体标题
    plt.suptitle(f'性能优化对比: {throughput_improvement:.1f}% 吞吐量提升, {latency_improvement:.1f}% 延迟改进', 
                 fontsize=16)
    
    plt.tight_layout(rect=[0, 0, 1, 0.95])
    
    # 保存图像
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    output_path = f'performance-results/comparison_{timestamp}.png'
    plt.savefig(output_path)
    print(f"对比图已保存到: {output_path}")
    
    # 显示图像
    plt.show()

def main():
    parser = argparse.ArgumentParser(description='可视化DisruptorX性能测试结果')
    parser.add_argument('--baseline', help='基线版本结果目录')
    parser.add_argument('--optimized', help='优化版本结果目录')
    
    args = parser.parse_args()
    
    # 如果没有指定目录，查找最新的两个结果目录
    if not args.baseline or not args.optimized:
        dirs = sorted(glob.glob('performance-results/*/'), reverse=True)
        if len(dirs) < 2:
            print("找不到足够的测试结果目录进行对比")
            sys.exit(1)
        baseline_dir = dirs[1]  # 次新的目录作为基线
        optimized_dir = dirs[0]  # 最新的目录作为优化版本
    else:
        baseline_dir = args.baseline
        optimized_dir = args.optimized
    
    print(f"比较基线版本: {baseline_dir}")
    print(f"与优化版本: {optimized_dir}")
    
    compare_benchmarks(baseline_dir, optimized_dir)

if __name__ == "__main__":
    main() 