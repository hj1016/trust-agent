"""명령행 진입점. 준비안 JSON을 stdout에 쓰고 종료 코드로 결과를 구분한다.

0: Core에 기록됨(RECORDED, ALREADY_RECORDED). 상태 READY·PARTIAL·HOLD 모두 기록 대상이다.
2: 입력 오류(신청·매핑 없음, 날짜 형식 등). 3: 기록 거부·실패·미시도(준비안은 출력됨, 사용 금지 안내). 4: 설정 누락.
오류 메시지에 토큰 값은 없다.
"""
from __future__ import annotations

import argparse
import json
import sys
from typing import Optional, Sequence

from .assembler import InputError, prepare
from .config import ENV_REPOSITORY_ROOT, SettingsError, load_settings

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
    try:
        preparation = prepare(args.application, args.business_date, args.consultation_id, settings=settings, **prepare_overrides)
    except InputError as error:
        print(f"입력 오류({error.code}): {error}", file=err)
        return EXIT_INPUT_ERROR
    print(json.dumps(preparation, ensure_ascii=False, indent=2), file=out)
    if preparation["record"]["recorded"]:
        return EXIT_RECORDED
    print("기록되지 않은 준비안입니다. 사용하지 말고 다시 실행하세요: " + preparation["record"]["status"]
          + " " + preparation["record"].get("error_code", ""), file=err)
    return EXIT_NOT_RECORDED
