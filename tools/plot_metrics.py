"""
Turn a metrics CSV from the Wired Tooth receiver into a PNG.

    python tools/plot_metrics.py metrics_20260906_014500.csv
    python tools/plot_metrics.py metrics_*.csv -o before_drift_fix.png

Two stacked subplots sharing an x axis:
    top     buffer depth over time
    bottom  latency over time (estimated end-to-end, and median RTT)

The buffer plot is the one that matters. A flat line means the sender and
receiver clocks are being held in step. A line that slopes up or drifts down
over tens of minutes is clock drift, and it is the failure WP4 exists to fix
-- it will look perfectly fine for the first two minutes, so short runs prove
nothing. Target depth is drawn as a reference line to make the slope obvious.
"""
import argparse
import csv
import sys
from pathlib import Path

try:
    import matplotlib
    matplotlib.use("Agg")            # no display needed, write straight to file
    import matplotlib.pyplot as plt
except ImportError:
    sys.exit("matplotlib is required:  pip install matplotlib")

TARGET_BUFFER_MS = 60.0              # WP4's setpoint
BUDGET_MS = 200.0                    # the project's latency claim


def read(path):
    rows = {}
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None:
            sys.exit(f"{path}: empty file")
        for name in reader.fieldnames:
            rows[name] = []
        for row in reader:
            for name in reader.fieldnames:
                value = row.get(name, "")
                try:
                    rows[name].append(float(value))
                except (TypeError, ValueError):
                    rows[name].append(float("nan"))
    if not rows.get("elapsed_s"):
        sys.exit(f"{path}: no data rows")
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", help="metrics_*.csv from the receiver")
    ap.add_argument("-o", "--out", help="output PNG (default: alongside the CSV)")
    a = ap.parse_args()

    src = Path(a.csv)
    if not src.exists():
        sys.exit(f"{src} not found")
    out = Path(a.out) if a.out else src.with_suffix(".png")

    d = read(src)
    t = d["elapsed_s"]

    fig, (ax_buf, ax_lat) = plt.subplots(
        2, 1, figsize=(11, 7), sharex=True,
        gridspec_kw={"height_ratios": [1, 1], "hspace": 0.12})

    # ---- buffer depth ------------------------------------------------
    ax_buf.plot(t, d["buffer_ms"], linewidth=1.4, label="buffer depth")
    ax_buf.axhline(TARGET_BUFFER_MS, linestyle="--", linewidth=1,
                   color="grey", label=f"target {TARGET_BUFFER_MS:.0f} ms")
    ax_buf.set_ylabel("buffer depth (ms)")
    ax_buf.set_title(f"Wired Tooth receiver metrics - {src.name}")
    ax_buf.grid(alpha=0.3)
    ax_buf.legend(loc="upper right", fontsize=9)
    ax_buf.set_ylim(bottom=0)

    # ---- latency -----------------------------------------------------
    ax_lat.plot(t, d["est_latency_ms"], linewidth=1.4,
                label="estimated end-to-end")
    if "rtt_ms" in d:
        ax_lat.plot(t, d["rtt_ms"], linewidth=1.0, alpha=0.8,
                    label="median RTT")
    if "transit_jitter_ms" in d:
        ax_lat.plot(t, d["transit_jitter_ms"], linewidth=0.9, alpha=0.7,
                    label="transit jitter (peak/s)")
    ax_lat.axhline(BUDGET_MS, linestyle="--", linewidth=1, color="grey",
                   label=f"budget {BUDGET_MS:.0f} ms")
    ax_lat.set_ylabel("latency (ms)")
    ax_lat.set_xlabel("elapsed (s)")
    ax_lat.grid(alpha=0.3)
    ax_lat.legend(loc="upper right", fontsize=9)
    ax_lat.set_ylim(bottom=0)

    # A run with zero loss and zero underruns is the claim being made, so it
    # is stated on the chart rather than left for the reader to assume.
    lost = int(d["packets_lost"][-1]) if d.get("packets_lost") else 0
    recv = int(d["packets_received"][-1]) if d.get("packets_received") else 0
    under = int(d["underruns"][-1]) if d.get("underruns") else 0
    total = recv + lost
    loss_pct = (lost / total * 100.0) if total else 0.0
    fig.text(0.01, 0.01,
             f"{recv:,} received | {lost:,} lost ({loss_pct:.2f}%) | "
             f"{under} underruns | {t[-1]:.0f}s run",
             fontsize=8, color="dimgrey")

    fig.savefig(out, dpi=140, bbox_inches="tight")
    print(f"wrote {out}  ({t[-1]:.0f}s, {len(t)} rows)")


if __name__ == "__main__":
    main()
