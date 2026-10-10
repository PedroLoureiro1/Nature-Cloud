#!/usr/bin/env python3

from __future__ import annotations

import math
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


WORKSPACE_DIR = Path(__file__).resolve().parents[1]
PROFILER_DATA_DIR = WORKSPACE_DIR / "data"
PLOTS_DIR = WORKSPACE_DIR / "profiler" / "plots" / "input_correlations"
SUMMARY_FILE = WORKSPACE_DIR / "analysis" / "input_metric_correlation_summary.csv"
OFAT_SLICES_DIR = WORKSPACE_DIR / "analysis" / "ofat_slices"

METRICS = ["MethodCalls", "BasicBlocks", "BytecodeInstructions", "LoopIterations", "weightedInstructions", "WeightedInstructionsExternalInvocations"]
METRIC_DIRS = {
    "MethodCalls": "method_calls",
    "BasicBlocks": "basic_blocks",
    "BytecodeInstructions": "bytecode_instructions",
    "LoopIterations": "loop_iteration",
    "weightedInstructions": "weighted_instructions",
    "WeightedInstructionsExternalInvocations": "weighted_instructions_external",
}
METRIC_VALUE_COLUMNS = {
    "MethodCalls": "method_calls",
    "BasicBlocks": "basic_blocks",
    "BytecodeInstructions": "bytecode_instructions",
    "LoopIterations": "loop_iteration",
    "weightedInstructions": "weighted_instructions",
    "WeightedInstructionsExternalInvocations": "weighted_instructions",
}

WORKLOADS = {
    "dna": {
        "label": "DNA",
        "params": ["seq1_length", "seq2_length", "minLength"],
        "controls": ["seq1_length", "seq2_length", "minLength"],
        "baseline": {
            "seq1_length": 1000,
            "seq2_length": 1000,
            "minLength": 10,
        },
    },
    "fractals": {
        "label": "Fractals",
        "params": ["width", "height", "iterations"],
        "controls": ["width", "height", "iterations"],
        "baseline": {
            "width": 800,
            "height": 800,
            "iterations": 800,
        },
    },
    "grayscott": {
        "label": "GrayScott",
        "params": ["size", "maxIterations", "f", "k", "stopOnExtinction"],
        "controls": ["size", "maxIterations", "f", "k", "stopOnExtinction", "seedMode"],
        "baseline": {
            "size": 400,
            "maxIterations": 600,
            "f": 0.030,
            "k": 0.062,
            "stopOnExtinction": False,
            "seedMode": "center",
        },
    },
}


def coerce_numeric(series: pd.Series) -> pd.Series:
    if series.dtype == bool:
        return series.astype(float)

    if pd.api.types.is_numeric_dtype(series):
        return series.astype(float)

    lowered = series.astype(str).str.lower()
    if lowered.isin({"true", "false"}).all():
        return lowered.map({"true": 1.0, "false": 0.0})

    converted = pd.to_numeric(series, errors="coerce")
    if converted.notna().any():
        return converted

    return pd.Series(dtype=float)


def pearson_r(x: pd.Series, y: pd.Series) -> float:
    values_x = np.asarray(x, dtype=float)
    values_y = np.asarray(y, dtype=float)

    mask = np.isfinite(values_x) & np.isfinite(values_y)
    values_x = values_x[mask]
    values_y = values_y[mask]

    if len(values_x) < 2 or len(np.unique(values_x)) < 2 or len(np.unique(values_y)) < 2:
        return math.nan

    return float(np.corrcoef(values_x, values_y)[0, 1])


def trendline(x: pd.Series, y: pd.Series) -> tuple[np.ndarray, np.ndarray] | None:
    values_x = np.asarray(x, dtype=float)
    values_y = np.asarray(y, dtype=float)
    mask = np.isfinite(values_x) & np.isfinite(values_y)
    values_x = values_x[mask]
    values_y = values_y[mask]

    if len(values_x) < 2 or len(np.unique(values_x)) < 2:
        return None

    slope, intercept = np.polyfit(values_x, values_y, 1)
    fitted_x = np.linspace(values_x.min(), values_x.max(), 100)
    fitted_y = slope * fitted_x + intercept
    return fitted_x, fitted_y


def matches_baseline(series: pd.Series, baseline_value: object) -> pd.Series:
    if isinstance(baseline_value, bool):
        lowered = series.astype(str).str.lower().str.strip()
        return lowered == str(baseline_value).lower()

    if isinstance(baseline_value, (int, float)):
        numeric = pd.to_numeric(series, errors="coerce")
        if numeric.notna().any():
            return pd.Series(
                np.isclose(numeric.to_numpy(dtype=float), float(baseline_value), rtol=1e-9, atol=1e-9),
                index=series.index,
            )

    return series.astype(str).str.strip().str.lower() == str(baseline_value).strip().lower()


def build_ofat_slice(workload_df: pd.DataFrame, workload_key: str, input_name: str) -> pd.DataFrame:
    if "sweep_variable" in workload_df.columns:
        sweep_mask = workload_df["sweep_variable"].astype(str).str.strip() == input_name
        sweep_slice = workload_df[sweep_mask].copy()
        if not sweep_slice.empty:
            return sweep_slice

    workload_info = WORKLOADS[workload_key]
    controls = workload_info.get("controls", workload_info["params"])
    baselines = workload_info.get("baseline", {})

    mask = pd.Series(True, index=workload_df.index)
    for control in controls:
        if control == input_name:
            continue
        if control not in workload_df.columns or control not in baselines:
            continue
        mask &= matches_baseline(workload_df[control], baselines[control])

    return workload_df[mask].copy()


def load_metric_data() -> pd.DataFrame:
    frames = []

    for metric_name in METRICS:
        metric_dir = PROFILER_DATA_DIR / METRIC_DIRS[metric_name]
        for workload_key, workload_info in WORKLOADS.items():
            csv_path = metric_dir / f"{workload_key}_profiling_results.csv"
            if not csv_path.exists():
                continue

            frame = pd.read_csv(csv_path).dropna(axis=1, how="all")
            frame["metric_name"] = metric_name
            frame["workload_name"] = workload_info["label"]
            frame["source_file"] = csv_path.name
            frames.append(frame)

    if not frames:
        raise FileNotFoundError(f"No profiler CSV files found under {PROFILER_DATA_DIR}")

    return pd.concat(frames, ignore_index=True)


def plot_workload(metric_name: str, workload_key: str, workload_df: pd.DataFrame) -> list[dict[str, object]]:
    workload_info = WORKLOADS[workload_key]
    time_column = "execution_time_ms"
    metric_columns = [c for c in workload_df.columns if c not in {"metric_name", "workload_name", "source_file"}]

    if time_column not in workload_df.columns:
        raise KeyError(f"Expected {time_column} column in {metric_name}/{workload_key}")

    metric_value_column = METRIC_VALUE_COLUMNS[metric_name]
    if metric_value_column not in workload_df.columns:
        numeric_candidates = [
            column_name
            for column_name in metric_columns
            if column_name != time_column and pd.api.types.is_numeric_dtype(workload_df[column_name])
        ]
        if not numeric_candidates:
            raise KeyError(f"Could not determine metric value column for {metric_name}/{workload_key}")
        metric_value_column = numeric_candidates[-1]

    plot_items: list[tuple[str, pd.DataFrame, float, float]] = []
    summary_rows: list[dict[str, object]] = []

    for input_name in workload_info["params"]:
        if input_name not in workload_df.columns:
            continue

        plot_frame = build_ofat_slice(workload_df, workload_key, input_name)
        if plot_frame.empty:
            continue

        plot_frame[input_name] = coerce_numeric(plot_frame[input_name])
        plot_frame = plot_frame.dropna(subset=[input_name, metric_value_column, time_column])
        if plot_frame.empty or len(np.unique(plot_frame[input_name])) < 2:
            continue

        # Save one CSV slice per input variable (OFAT only).
        ofat_dir = OFAT_SLICES_DIR / metric_name.lower() / workload_key
        ofat_dir.mkdir(parents=True, exist_ok=True)
        plot_frame.to_csv(ofat_dir / f"{input_name}_ofat.csv", index=False)

        metric_r = pearson_r(plot_frame[input_name], plot_frame[metric_value_column])
        time_r = pearson_r(plot_frame[input_name], plot_frame[time_column])

        summary_rows.append(
            {
                "metric_name": metric_name,
                "workload_name": workload_info["label"],
                "input_name": input_name,
                "metric_column": metric_value_column,
                "r_input_metric": metric_r,
                "r_input_time": time_r,
                "rows": int(plot_frame[[input_name, metric_value_column, time_column]].dropna().shape[0]),
                "slice_type": "OFAT",
            }
        )

        plot_items.append((input_name, plot_frame, metric_r, time_r))

    if not plot_items:
        return []

    fig, axes = plt.subplots(len(plot_items), 2, figsize=(13, 4.2 * len(plot_items)))
    if len(plot_items) == 1:
        axes = np.array([axes])

    fig.suptitle(
        f"{metric_name} vs Inputs for {workload_info['label']} (OFAT Slices)",
        fontsize=16,
        fontweight="bold",
    )

    for row_index, (input_name, plot_frame, metric_r, time_r) in enumerate(plot_items):
        left_ax = axes[row_index, 0]
        left_ax.scatter(plot_frame[input_name], plot_frame[metric_value_column], s=65, alpha=0.8, color="tab:blue")
        fitted = trendline(plot_frame[input_name], plot_frame[metric_value_column])
        if fitted is not None:
            fit_x, fit_y = fitted
            left_ax.plot(fit_x, fit_y, color="black", linestyle="--", linewidth=2)
        left_ax.set_title(f"{input_name} vs {metric_value_column} (r={metric_r:.3f})")
        left_ax.set_xlabel(input_name)
        left_ax.set_ylabel(metric_value_column)
        left_ax.grid(True, alpha=0.3)

        right_ax = axes[row_index, 1]
        right_ax.scatter(plot_frame[input_name], plot_frame[time_column], s=65, alpha=0.8, color="tab:orange")
        fitted = trendline(plot_frame[input_name], plot_frame[time_column])
        if fitted is not None:
            fit_x, fit_y = fitted
            right_ax.plot(fit_x, fit_y, color="black", linestyle="--", linewidth=2)
        right_ax.set_title(f"{input_name} vs execution_time_ms (r={time_r:.3f})")
        right_ax.set_xlabel(input_name)
        right_ax.set_ylabel("execution_time_ms")
        right_ax.grid(True, alpha=0.3)

    fig.tight_layout(rect=[0, 0, 1, 0.97])
    output_dir = PLOTS_DIR / metric_name.lower() / workload_key
    output_dir.mkdir(parents=True, exist_ok=True)
    output_file = output_dir / f"{workload_key}_input_correlations.png"
    fig.savefig(output_file, dpi=180, bbox_inches="tight")
    plt.close(fig)
    print(f"Saved {output_file}")

    return summary_rows


def main() -> None:
    PLOTS_DIR.mkdir(parents=True, exist_ok=True)
    OFAT_SLICES_DIR.mkdir(parents=True, exist_ok=True)
    data = load_metric_data()

    print(f"Loaded {len(data)} rows from {PROFILER_DATA_DIR}")
    print()

    summary_rows: list[dict[str, object]] = []

    for metric_name in METRICS:
        metric_df = data[data["metric_name"] == metric_name].copy()
        if metric_df.empty:
            continue

        for workload_key, workload_info in WORKLOADS.items():
            workload_df = metric_df[metric_df["workload_name"] == workload_info["label"]].copy()
            if workload_df.empty:
                continue

            rows = plot_workload(metric_name, workload_key, workload_df)
            summary_rows.extend(rows)

    if summary_rows:
        summary_df = pd.DataFrame(summary_rows)
        summary_df = summary_df.sort_values(["metric_name", "workload_name", "input_name"])
        SUMMARY_FILE.parent.mkdir(parents=True, exist_ok=True)
        summary_df.to_csv(SUMMARY_FILE, index=False)
        print(f"Saved {SUMMARY_FILE}")

        print()
        print(summary_df.to_string(index=False))


if __name__ == "__main__":
    main()
