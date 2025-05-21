#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import pandas as pd
import matplotlib.pyplot as plt
import glob

def find_latest_benchmark():
    """Find the most recent benchmark results directory"""
    advanced_dirs = sorted(glob.glob("benchmark-results/advanced-*"), reverse=True)
    if advanced_dirs:
        return advanced_dirs[0]
    else:
        print("No benchmark results found. Run advanced-benchmark.sh first.")
        sys.exit(1)

def plot_performance(benchmark_dir, rate=None):
    """Plot performance metrics for a specific benchmark or all benchmarks"""
    plt.style.use('ggplot')
    
    if rate:
        # Plot specific rate
        target_dirs = [os.path.join(benchmark_dir, d) for d in os.listdir(benchmark_dir) 
                      if d.endswith(f"{rate}msg") and os.path.isdir(os.path.join(benchmark_dir, d))]
    else:
        # Plot all rates
        target_dirs = [os.path.join(benchmark_dir, d) for d in os.listdir(benchmark_dir) 
                      if d.endswith("msg") and os.path.isdir(os.path.join(benchmark_dir, d))]
    
    if not target_dirs:
        print(f"No benchmark data found for rate {rate if rate else 'any'}.")
        return
    
    # Create figure with subplots
    fig, axs = plt.subplots(2, 2, figsize=(15, 10))
    fig.suptitle('DisruptorX 性能测试结果', fontsize=16)
    
    # For throughput comparison
    rates = []
    throughputs = []
    latencies = []
    
    for target_dir in target_dirs:
        test_name = os.path.basename(target_dir)
        rate_value = int(''.join(filter(str.isdigit, test_name.split('_')[-1])))
        rates.append(rate_value)
        
        # Read CSV data
        csv_path = os.path.join(target_dir, 'performance_data.csv')
        if os.path.exists(csv_path):
            df = pd.read_csv(csv_path)
            
            # Calculate average throughput and latency
            avg_throughput = df['throughput'].mean()
            throughputs.append(avg_throughput)
            
            # Median latency
            avg_p50 = df['p50_latency'].mean()
            avg_p99 = df['p99_latency'].mean()
            avg_p999 = df['p999_latency'].mean()
            latencies.append((avg_p50, avg_p99, avg_p999))
            
            # Plot throughput over time
            axs[0, 0].plot(df['timestamp'], df['throughput'], 
                        label=f"{rate_value}/s", linewidth=2)
            
            # Plot latency over time
            axs[0, 1].plot(df['timestamp'], df['p50_latency'], 
                        label=f"{rate_value}/s 中位数", linewidth=2, linestyle='-')
            axs[0, 1].plot(df['timestamp'], df['p99_latency'], 
                        label=f"{rate_value}/s 99%", linewidth=1, linestyle='--')
            
            # Read report data
            report_path = os.path.join(target_dir, 'report.txt')
            if os.path.exists(report_path):
                try:
                    with open(report_path, 'r', encoding='utf-8') as f:
                        report_content = f.read()
                except UnicodeDecodeError:
                    # Try alternative encodings
                    try:
                        with open(report_path, 'r', encoding='latin-1') as f:
                            report_content = f.read()
                    except:
                        report_content = ""
                        print(f"Warning: Could not read report file {report_path}")
                    
                # Check if fault injection occurred
                if "故障注入" in report_content:
                    fault_time = None
                    # Estimate fault injection time (rough approximation)
                    timestamps = df['timestamp'].values
                    for i in range(1, len(timestamps) - 1):
                        if timestamps[i+1] - timestamps[i] > 3:  # Gap in timestamps
                            fault_time = timestamps[i]
                            break
                    
                    if fault_time is not None:
                        # Mark fault injection
                        axs[0, 0].axvline(x=fault_time, color='red', linestyle='--', 
                                       label='故障注入' if rate_value == rates[0] else None)
                        axs[0, 1].axvline(x=fault_time, color='red', linestyle='--')
    
    # Sort by rate
    rate_data = sorted(zip(rates, throughputs, latencies))
    rates = [r for r, _, _ in rate_data]
    throughputs = [t for _, t, _ in rate_data]
    p50_latencies = [l[0] for _, _, l in rate_data]
    p99_latencies = [l[1] for _, _, l in rate_data]
    p999_latencies = [l[2] for _, _, l in rate_data]
    
    # Plot throughput comparison
    axs[1, 0].bar(range(len(rates)), throughputs, color='skyblue')
    axs[1, 0].set_xticks(range(len(rates)))
    axs[1, 0].set_xticklabels([f"{r}/s" for r in rates])
    
    # Add target line
    for i, r in enumerate(rates):
        axs[1, 0].plot([i-0.4, i+0.4], [r, r], 'r--', linewidth=1)
    
    # Plot latency comparison
    x = range(len(rates))
    width = 0.25
    axs[1, 1].bar([i-width for i in x], p50_latencies, width, label='中位数延迟', color='green')
    axs[1, 1].bar([i for i in x], p99_latencies, width, label='99%延迟', color='orange')
    axs[1, 1].bar([i+width for i in x], p999_latencies, width, label='99.9%延迟', color='red')
    axs[1, 1].set_xticks(x)
    axs[1, 1].set_xticklabels([f"{r}/s" for r in rates])
    axs[1, 1].set_yscale('log')
    
    # Set labels and legends
    axs[0, 0].set_title('吞吐量随时间变化')
    axs[0, 0].set_xlabel('时间 (秒)')
    axs[0, 0].set_ylabel('吞吐量 (订单/秒)')
    axs[0, 0].legend()
    axs[0, 0].grid(True)
    
    axs[0, 1].set_title('延迟随时间变化')
    axs[0, 1].set_xlabel('时间 (秒)')
    axs[0, 1].set_ylabel('延迟 (微秒)')
    axs[0, 1].legend()
    axs[0, 1].grid(True)
    
    axs[1, 0].set_title('不同速率的平均吞吐量')
    axs[1, 0].set_xlabel('目标速率')
    axs[1, 0].set_ylabel('实际吞吐量 (订单/秒)')
    axs[1, 0].grid(True)
    
    axs[1, 1].set_title('不同速率的延迟分布')
    axs[1, 1].set_xlabel('目标速率')
    axs[1, 1].set_ylabel('延迟 (微秒, 对数尺度)')
    axs[1, 1].legend()
    axs[1, 1].grid(True)
    
    plt.tight_layout()
    
    # Save figure
    output_dir = os.path.join(benchmark_dir, "visualizations")
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
    
    output_file = os.path.join(output_dir, f"performance_{'_'.join(map(str, rates))}.png")
    plt.savefig(output_file, dpi=150)
    print(f"图表已保存到: {output_file}")
    
    # Try to display if not in headless mode
    try:
        plt.show()
    except:
        pass

def main():
    benchmark_dir = find_latest_benchmark()
    print(f"使用最新的基准测试结果: {benchmark_dir}")
    
    if len(sys.argv) > 1:
        rate = sys.argv[1]
        print(f"绘制速率 {rate} 的性能数据")
        plot_performance(benchmark_dir, rate)
    else:
        print("绘制所有速率的性能数据")
        plot_performance(benchmark_dir)

if __name__ == "__main__":
    main() 