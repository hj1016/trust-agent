"""명령행 진입점. 준비안 JSON을 stdout에 쓰고 종료 코드로 결과를 구분한다.

0: Core에 기록됨(RECORDED, ALREADY_RECORDED). 상태 READY·PARTIAL·HOLD 모두 기록 대상이다.
2: 입력 오류(신청·매핑 없음, 날짜 형식 등). 3: 기록 거부·실패·미시도(준비안은 출력됨, 사용 금지 안내. HTTP 진입점의 422/409/503/500/502/504와 같은 상황). 4: 설정 누락.
오류 메시지에 토큰 값은 없다.

--metrics-file <경로>(TASK-021): Core 호출마다의 소요 시간과 횟수를 별도 JSON 파일에 쓴다. stdout의 준비안과 종료 코드는 바뀌지 않는다.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional, Sequence

from .assembler import InputError, prepare
from .config import ENV_REPOSITORY_ROOT, SettingsError, load_settings
from .core_client import UrllibTransport
from .metrics import TimingTransport, metrics_document, write_metrics

EXIT_RECORDED = 0
EXIT_INPUT_ERROR = 2
EXIT_NOT_RECORDED = 3
EXIT_SETTINGS_ERROR = 4


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ai_service", description="TrustAgent AI 서비스(규칙 조립 상담 준비안과 보류)")
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_command = commands.add_parser("prepare", help="합성 신청 건의 상담 준비안을 조립하고 Core에 기록한다")
    prepare_command.add_argument("--application", required=True, help="합성 신청 ID (예: SW-APPLICATION-001)")
    prepare_command.add_argument("--business-date", help="업무일 YYYY-MM-DD (생략 시 서울 오늘)")
    prepare_command.add_argument("--consultation-id", help="추적용 상담 ID (64자 이내)")
    prepare_command.add_argument("--repository-root", help=f"저장소 루트 (생략 시 {ENV_REPOSITORY_ROOT} 또는 패키지 위치 기준)")
    prepare_command.add_argument("--metrics-file", help="Core 호출 계측 결과를 쓸 JSON 파일 경로 (준비안 출력은 바뀌지 않음)")
    return parser


def main(argv: Optional[Sequence[str]] = None, environ: Optional[dict] = None, stdout=None, stderr=None, **prepare_overrides) -> int:
    out = stdout or sys.stdout
    err = stderr or sys.stderr
    args = build_parser().parse_args(argv)
    env = dict(environ) if environ is not None else None
    if args.repository_root:
        if env is None:
            import os
            env = dict(os.environ)
        env[ENV_REPOSITORY_ROOT] = args.repository_root
    try:
        settings = load_settings(env)
    except SettingsError as error:
        print(f"설정 오류({error.code}): {error}", file=err)
        return EXIT_SETTINGS_ERROR

    timing: Optional[TimingTransport] = None
    if args.metrics_file:
        # 계측은 전송 계층을 감싸기만 한다. 호출 순서·횟수·결과는 계측이 없을 때와 같다.
        timing = TimingTransport(prepare_overrides.pop("transport", None) or UrllibTransport())
        prepare_overrides["transport"] = timing
    started_at = datetime.now(timezone.utc)
    started = time.perf_counter()
    try:
        preparation = prepare(args.application, args.business_date, args.consultation_id, settings=settings, **prepare_overrides)
    except InputError as error:
        print(f"입력 오류({error.code}): {error}", file=err)
        return EXIT_INPUT_ERROR
    total_elapsed_us = int((time.perf_counter() - started) * 1_000_000)
    print(json.dumps(preparation, ensure_ascii=False, indent=2), file=out)
    if timing is not None:
        try:
            write_metrics(Path(args.metrics_file), metrics_document(preparation, timing, started_at, total_elapsed_us))
        except OSError as error:
            print(f"계측 파일을 쓰지 못했습니다({error.__class__.__name__}). 준비안 결과에는 영향이 없습니다.", file=err)
    if preparation["record"]["recorded"]:
        return EXIT_RECORDED
    print("기록되지 않은 준비안입니다. 사용하지 말고 안내를 따르세요: " + preparation["record"]["status"]
          + " " + preparation["record"].get("error_code", "") + " — " + preparation["notices"]["usage_notice"], file=err)
    return EXIT_NOT_RECORDED
