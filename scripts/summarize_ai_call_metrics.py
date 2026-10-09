#!/usr/bin/env python3
"""AI 호출 계측 요약(TASK-021). <입력 디렉터리>/<시나리오>/*.json(ai-call-metrics-v1)을 읽어 단계별·전체 p50/p95를 표로 만든다.

백분위는 최근접 순위(nearest-rank) 방식이다: 오름차순 정렬 뒤 index = ceil(p/100 * n) - 1.
예열(warm-up) 실행은 파일 이름 순으로 앞에서 --warmup 개를 제외하고 그 수를 표에 적는다. 입력이 없거나 버전이 다르면 거부한다(종료 코드 2).
수치는 실제 실행 파일에서만 온다.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Iterable, Sequence

METRICS_VERSION = "ai-call-metrics-v1"
CALL_KINDS = ("applicable_checklist", "rule_evidence", "record")
PERCENTILES = (50, 95)


class SummaryError(ValueError):
    pass


def percentile(values: Sequence[int], p: int) -> int:
    """최근접 순위 백분위. 빈 입력은 오류."""
    if not values:
        raise SummaryError("EMPTY_INPUT")
    ordered = sorted(values)
    index = max(math.ceil(p / 100 * len(ordered)) - 1, 0)
    return ordered[index]


def load_scenarios(input_dir: Path) -> dict[str, list[dict]]:
    if not input_dir.is_dir():
        raise SummaryError(f"NO_INPUT_DIR: {input_dir}")
    scenarios: dict[str, list[dict]] = {}
    for scenario_dir in sorted(path for path in input_dir.iterdir() if path.is_dir()):
        documents = []
        for file in sorted(scenario_dir.glob("*.json")):
            with file.open(encoding="utf-8") as source:
                document = json.load(source)
            if document.get("metrics_version") != METRICS_VERSION:
                raise SummaryError(f"VERSION_MISMATCH: {file}")
            documents.append(document)
        if documents:
            scenarios[scenario_dir.name] = documents
    if not scenarios:
        raise SummaryError(f"NO_METRICS_FILES: {input_dir}")
    return scenarios


def summarize(documents: Sequence[dict], warmup: int) -> dict:
    if warmup < 0:
        raise SummaryError("NEGATIVE_WARMUP")
    measured = list(documents[warmup:])
    if not measured:
        raise SummaryError("ALL_RUNS_EXCLUDED_BY_WARMUP")
    totals = [doc["total_elapsed_us"] for doc in measured]
    assembly = [doc["assembly_elapsed_us"] for doc in measured]
    per_kind = {}
    for kind in CALL_KINDS:
        durations = [call["elapsed_us"] for doc in measured for call in doc["calls"] if call["kind"] == kind]
        counts = [doc["counts"][kind] for doc in measured]
        per_kind[kind] = {
            "calls": len(durations),
            "count_per_run_min": min(counts),
            "count_per_run_max": max(counts),
            **({f"p{p}_us": percentile(durations, p) for p in PERCENTILES} if durations else {}),
            **({"min_us": min(durations), "max_us": max(durations)} if durations else {}),
        }
    record_statuses: dict[str, int] = {}
    for doc in measured:
        record_statuses[doc["record_status"]] = record_statuses.get(doc["record_status"], 0) + 1
    outcomes: dict[str, int] = {}
    for doc in measured:
        for call in doc["calls"]:
            outcomes[call["outcome"]] = outcomes.get(call["outcome"], 0) + 1
    return {
        "runs_total": len(documents),
        "warmup_excluded": min(warmup, len(documents)),
        "runs_measured": len(measured),
        "statuses": sorted({doc["status"] for doc in measured}),
        "record_statuses": record_statuses,
        "call_outcomes": outcomes,
        "total": {f"p{p}_us": percentile(totals, p) for p in PERCENTILES} | {"min_us": min(totals), "max_us": max(totals)},
        "assembly": {f"p{p}_us": percentile(assembly, p) for p in PERCENTILES},
        "per_kind": per_kind,
    }


def _ms(value_us: int) -> str:
    return f"{value_us / 1000:.1f}"


def render_markdown(summaries: dict[str, dict]) -> str:
    lines = ["| 시나리오 | 측정 N(예열 제외) | 상태 | 기록 결과 | 전체 p50 / p95 (ms) | Tool 1 호출 수/실행, p50 / p95 (ms) | Tool 2 호출 수/실행, p50 / p95 (ms) | 기록 호출 p50 / p95 (ms) | 조립(호출 외) p50 / p95 (ms) | 호출 결과 |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    for name, s in summaries.items():
        def kind_cell(kind: str) -> str:
            k = s["per_kind"][kind]
            count = f"{k['count_per_run_min']}" if k["count_per_run_min"] == k["count_per_run_max"] else f"{k['count_per_run_min']}~{k['count_per_run_max']}"
            if "p50_us" not in k:
                return f"{count}, 없음"
            return f"{count}, {_ms(k['p50_us'])} / {_ms(k['p95_us'])}"
        lines.append("| " + " | ".join([
            name,
            f"{s['runs_measured']}({s['warmup_excluded']})",
            ", ".join(s["statuses"]),
            ", ".join(f"{k} {v}" for k, v in sorted(s["record_statuses"].items())),
            f"{_ms(s['total']['p50_us'])} / {_ms(s['total']['p95_us'])}",
            kind_cell("applicable_checklist"),
            kind_cell("rule_evidence"),
            kind_cell("record"),
            f"{_ms(s['assembly']['p50_us'])} / {_ms(s['assembly']['p95_us'])}",
            ", ".join(f"{k} {v}" for k, v in sorted(s["call_outcomes"].items())),
        ]) + " |")
    return "\n".join(lines) + "\n"


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="AI 호출 계측 파일 요약")
    parser.add_argument("--input-dir", required=True, help="<디렉터리>/<시나리오>/*.json")
    parser.add_argument("--warmup", type=int, default=0, help="시나리오별로 앞에서 제외할 예열 실행 수")
    parser.add_argument("--markdown", help="요약 표를 쓸 Markdown 파일(생략 시 stdout)")
    parser.add_argument("--json", help="요약 수치를 쓸 JSON 파일")
    args = parser.parse_args(list(argv) if argv is not None else None)
    try:
        scenarios = load_scenarios(Path(args.input_dir))
        summaries = {name: summarize(docs, args.warmup) for name, docs in scenarios.items()}
    except SummaryError as error:
        print(f"요약 거부: {error}", file=sys.stderr)
        return 2
    markdown = render_markdown(summaries)
    if args.markdown:
        Path(args.markdown).write_text(markdown, encoding="utf-8")
    else:
        sys.stdout.write(markdown)
    if args.json:
        Path(args.json).write_text(json.dumps(summaries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
