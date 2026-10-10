#!/usr/bin/env python3
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
from pathlib import Path
import sys

# Set style
sns.set_theme(style="whitegrid")
plt.rcParams['figure.figsize'] = (14, 8)

RESULTS_DIR = Path(__file__).parent / "overhead" / "results"
OUTPUT_DIR = RESULTS_DIR / "plots"
OUTPUT_DIR.mkdir(exist_ok=True)

# Load CSV files
metrics = ["MethodCalls", "BasicBlocks", "BytecodeInstructions", "LoopIterations", "WeightedInstructions", "WeightedInstructionsExternalInvocations"]
workloads = ["dna", "fractals", "grayscott"]

all_data = []
for metric in metrics:
    for workload in workloads:
        csv_file = RESULTS_DIR / f"{metric}_{workload}_overhead.csv"
        if csv_file.exists():
            df = pd.read_csv(csv_file)
            df['metric_type'] = metric
            all_data.append(df)

if not all_data:
    print("ERROR: No CSV files found in", RESULTS_DIR)
    sys.exit(1)

df_all = pd.concat(all_data, ignore_index=True)

# Plot 1: Bar chart - Overhead % by Metric across Workloads
fig, ax = plt.subplots(figsize=(12, 6))
sns.barplot(data=df_all, x='workload', y='overhead_percent', hue='metric_type', ax=ax)
ax.set_title('Instrumentation Overhead % by Metric', fontsize=14, fontweight='bold')
ax.set_xlabel('Workload', fontsize=12)
ax.set_ylabel('Overhead (%)', fontsize=12)
plt.tight_layout()
plt.savefig(OUTPUT_DIR / "overhead_by_metric.png", dpi=300, bbox_inches='tight')
print(f"✓ Saved: {OUTPUT_DIR / 'overhead_by_metric.png'}")
plt.close()

# Print summary statistics
print("\n" + "="*60)
print("OVERHEAD SUMMARY STATISTICS")
print("="*60)
for metric in metrics:
    df_metric = df_all[df_all['metric_type'] == metric]
    avg_overhead = df_metric['overhead_percent'].mean()
    min_overhead = df_metric['overhead_percent'].min()
    max_overhead = df_metric['overhead_percent'].max()
    print(f"\n{metric}:")
    print(f"  Average Overhead: {avg_overhead:.2f}%")
    print(f"  Min Overhead:     {min_overhead:.2f}% (best case)")
    print(f"  Max Overhead:     {max_overhead:.2f}% (worst case)")

print("\n" + "="*60)
print(f"All plots saved to: {OUTPUT_DIR}")
print("="*60)
