import csv
import json
import pathlib
from typing import Optional, Tuple

import matplotlib.pyplot as plt

# Входной файл отчёта JMH (CSV или JSON) и куда сохранить график — при необходимости изменить здесь.
_PROJECT_ROOT = pathlib.Path(__file__).resolve().parent.parent
JMH_RESULTS_FILE = _PROJECT_ROOT / "target" / "jmh-results" / "ConcurrentHashMapBenchmark-20260515-193137.csv"
JMH_CHART_OUTPUT = _PROJECT_ROOT / "target" / "jmh-results" / "jmh-chart-1.png"
def parse_bench_name(raw_name: str) -> str:
    name = raw_name.split(".")[-1]
    if name.startswith("concurrent"):
        name = "custom" + name[len("concurrent") :]
    return name.replace("syncMap", "synchronized").replace("plainMap", "plain")


def _parse_jmh_number(s: str) -> float:
    return float(s.strip().strip('"').replace(",", "."))


def load_csv(csv_path: pathlib.Path) -> Tuple[list, str, str]:
    """Читает стандартный CSV-отчёт JMH (ResultFormatType.CSV)."""
    rows = []
    unit = "us/op"
    mode = "avgt"
    with csv_path.open(newline="", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        size_col = next(
            (c for c in reader.fieldnames or [] if c.startswith("Param:") and "size" in c.lower()),
            "Param: size",
        )
        for row in reader:
            raw_bench = (row.get("Benchmark") or "").strip().strip('"')
            score_s = (row.get("Score") or "").strip().strip('"')
            if not raw_bench or not score_s:
                continue
            try:
                size = int((row.get(size_col) or "").strip().strip('"'))
                score = _parse_jmh_number(score_s)
            except ValueError:
                continue
            bench = parse_bench_name(raw_bench)
            u = (row.get("Unit") or "").strip().strip('"')
            if u:
                unit = u
            m = (row.get("Mode") or "").strip().strip('"')
            if m:
                mode = m
            rows.append((size, bench, score))
    return rows, unit, mode


def load_json(json_path: pathlib.Path) -> Tuple[list, Optional[str], Optional[str]]:
    with json_path.open("r", encoding="utf-8") as f:
        data = json.load(f)

    rows = []
    for item in data:
        score = item.get("primaryMetric", {}).get("score")
        if score is None:
            continue
        params = item.get("params", {})
        size = int(params.get("size", 0))
        bench = parse_bench_name(item.get("benchmark", "unknown"))
        rows.append((size, bench, float(score)))
    return rows, None, None


def load_points(input_path: pathlib.Path) -> Tuple[list, Optional[str], Optional[str]]:
    suffix = input_path.suffix.lower()
    if suffix == ".csv":
        return load_csv(input_path)
    if suffix == ".json":
        return load_json(input_path)
    raise ValueError(f"Неподдерживаемый формат файла: {suffix} (ожидаются .csv или .json)")


def _ylabel_for(unit: Optional[str], mode: Optional[str]) -> str:
    if unit and mode == "avgt" and unit == "us/op":
        return "Среднее время (мкс/оп)"
    if unit:
        return f"Score ({unit})"
    return "Throughput (ops/s)"


def plot(rows, out_path: pathlib.Path, unit: Optional[str], mode: Optional[str]):
    sizes = sorted({r[0] for r in rows})
    benches = sorted({r[1] for r in rows})

    fig, ax = plt.subplots(figsize=(9, 5))
    for bench in benches:
        x = []
        y = []
        for size in sizes:
            candidates = [r[2] for r in rows if r[0] == size and r[1] == bench]
            if not candidates:
                continue
            x.append(size)
            y.append(sum(candidates) / len(candidates))
        if x:
            ax.plot(x, y, marker="o", label=bench)

    ax.set_xlabel("Размер карты (число ключей)")
    ax.set_ylabel(_ylabel_for(unit, mode))
    ax.set_title("ConcurrentHashMap benchmark (JMH)")
    ax.set_xscale("log")
    ax.grid(True, alpha=0.3)
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    print(f"Saved chart to {out_path}")


def main():
    input_path = JMH_RESULTS_FILE
    output_path = JMH_CHART_OUTPUT
    try:
        rows, unit, mode = load_points(input_path)
    except ValueError as e:
        print(e)
        raise SystemExit(2) from e
    if not rows:
        print("Во входном файле не найдено строк бенчмарка.")
        raise SystemExit(3)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    plot(rows, output_path, unit, mode)


if __name__ == "__main__":
    main()
