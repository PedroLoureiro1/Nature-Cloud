# Profiling Research Guide

This folder now uses a metric-first structure for both profilers and data.

## Structure

- `profiler/icount/`
  - `profile_dna.sh`
  - `profile_fractals.sh`
  - `profile_grayscott.sh`
- `profiler/basic_blocks/`
  - `profile_dna.sh`
  - `profile_fractals.sh`
  - `profile_grayscott.sh`
- `profiler/bytecode_instructions/`
  - `profile_dna.sh`
  - `profile_fractals.sh`
  - `profile_grayscott.sh`
- `profiler/method_calls/`
  - `profile_dna.sh`
  - `profile_fractals.sh`
  - `profile_grayscott.sh`
- `profiler/loop_iteration/run_profile.sh` - shared runner used by the loop-iteration profiler
- `profiler/icount/run_profile.sh` - shared runner used by the ICount profiler

- `data/icount/`
- `data/basic_blocks/`
- `data/bytecode_instructions/`
- `data/method_calls/`
- `data/loop_iteration/`

Each data subfolder contains:
- `<workload>_profiling_results.csv`
- `<workload>_correlations.png` (after analysis)

## Running Profilers

Run any metric/workload combination directly.

Examples:

```bash
bash research/profiler/profile_dna.sh
bash research/profiler/profile_fractals.sh
bash research/profiler/profile_grayscott.sh
bash research/profiler/icount/profile_dna.sh

bash research/profiler/basic_blocks/profile_dna.sh
bash research/profiler/method_calls/profile_grayscott.sh
bash research/profiler/loop_iteration/run_profile.sh fractals
```

## Running Analysis

Usage:

```bash
python3 research/analysis/correlate.py <metric> [workload]
```

Examples:

```bash
python3 research/analysis/correlate.py icount dna
python3 research/analysis/correlate.py icount all
python3 research/analysis/correlate.py basic_blocks all
python3 research/analysis/correlate.py method_calls grayscott
python3 research/analysis/correlate.py loop_iteration all
```

If only one argument is passed and it is a workload (`dna`, `fractals`, `grayscott`, `all`), metric defaults to `icount`.
