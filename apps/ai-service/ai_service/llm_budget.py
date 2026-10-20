"""외부 LLM(Bedrock) 호출 통제 원장. 저장소 밖 SQLite 파일 하나를 모든 프로세스·worktree가 함께 쓴다.

원칙
- 사용자가 승인한 예산(approval)이 등록돼 있지 않으면 호출 0회다. 예산 등록은 사람이 명령으로 한다(이 모듈의 grant 명령).
- 호출 전에 '보수적 추정 단가'로 입력·최대 출력 비용을 예약하고, 상한(호출 수·금액)을 넘으면 HTTP 요청 전에 막는다.
- 응답 뒤 usage로 정산한다. usage가 없거나 timeout·연결 오류·잘못된 응답이면 예약액을 그대로 확정한다(과금됐다고 가정).
- 동시 호출은 1건: 원장 옆 lock 파일에 배타 잠금(비대기). 잠금을 못 얻으면 즉시 LLM_BUSY.
- 질문 원문·근거 원문은 원장에 남기지 않는다(payload sha256만).
금액은 마이크로달러 정수로 다룬다. 추정 단가는 실제 청구 단가가 아니며 실제 청구액은 AWS 청구 화면으로 대조해야 한다.
"""
from __future__ import annotations

import argparse
import contextlib
import fcntl
import json
import os
import sqlite3
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator, Optional

ENV_LEDGER = "TRUST_AGENT_LLM_LEDGER"
DEFAULT_LEDGER = Path.home() / ".trust-agent" / "bedrock-ledger.sqlite"
MICRO = 1_000_000


class BudgetError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class Reservation:
    call_id: int
    approval_id: str
    reserved_micros: int


def ledger_path(environ: Optional[dict] = None) -> Path:
    env = os.environ if environ is None else environ
    raw = (env.get(ENV_LEDGER) or "").strip()
    return Path(raw) if raw else DEFAULT_LEDGER


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class Ledger:
    def __init__(self, path: Path) -> None:
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.executescript("""
                create table if not exists approval (
                    approval_id text primary key, max_calls integer not null check (max_calls >= 0),
                    max_micros integer not null check (max_micros >= 0), input_price_micros_per_mtok integer not null,
                    output_price_micros_per_mtok integer not null, expires_at text not null, purpose text not null,
                    registered_at text not null, note text not null);
                create table if not exists calls (
                    call_id integer primary key autoincrement, approval_id text not null references approval(approval_id),
                    purpose text not null, worktree text not null, model text not null, payload_sha256 text not null,
                    reserved_micros integer not null, charged_micros integer, input_tokens integer, output_tokens integer,
                    status text not null, reason text, created_at text not null, settled_at text);
            """)

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.execute("pragma journal_mode=wal")
        return db

    # ---- 사람이 하는 일: 예산 등록 ----
    def register(self, approval_id: str, max_calls: int, max_usd: float, input_usd_per_mtok: float, output_usd_per_mtok: float,
                 expires_at: str, purpose: str, note: str) -> None:
        with self._connect() as db:
            db.execute("insert into approval values (?,?,?,?,?,?,?,?,?)",
                       (approval_id, max_calls, round(max_usd * MICRO), round(input_usd_per_mtok * MICRO), round(output_usd_per_mtok * MICRO),
                        expires_at, purpose, _now(), note))

    # ---- 호출 전 예약 ----
    def reserve(self, approval_id: str, purpose: str, model: str, payload_sha256: str, input_tokens_estimate: int, max_output_tokens: int,
                worktree: str) -> Reservation:
        db = self._connect()
        try:
            db.execute("begin immediate")
            row = db.execute("select max_calls, max_micros, input_price_micros_per_mtok, output_price_micros_per_mtok, expires_at, purpose "
                             "from approval where approval_id = ?", (approval_id,)).fetchone()
            if row is None:
                raise BudgetError("LLM_BUDGET_NOT_APPROVED", "승인된 호출 예산이 없습니다.")
            max_calls, max_micros, in_price, out_price, expires_at, approved_purpose = row
            if _now() >= expires_at:
                raise BudgetError("LLM_BUDGET_EXPIRED", "호출 예산 승인 기간이 지났습니다.")
            if purpose != approved_purpose:
                raise BudgetError("LLM_BUDGET_PURPOSE_MISMATCH", "승인된 용도와 다른 호출입니다.")
            reserved = -(-(input_tokens_estimate * in_price + max_output_tokens * out_price) // 1_000_000)  # 올림
            used_calls, committed = db.execute(
                "select count(*), coalesce(sum(coalesce(charged_micros, reserved_micros)), 0) from calls where approval_id = ?", (approval_id,)).fetchone()
            if used_calls + 1 > max_calls:
                raise BudgetError("LLM_BUDGET_EXCEEDED", "승인된 호출 횟수를 넘습니다.")
            if committed + reserved > max_micros:
                raise BudgetError("LLM_BUDGET_EXCEEDED", "승인된 금액 상한을 넘습니다(보수적 추정 단가 기준).")
            cursor = db.execute("insert into calls (approval_id, purpose, worktree, model, payload_sha256, reserved_micros, status, created_at) "
                                "values (?,?,?,?,?,?, 'RESERVED', ?)", (approval_id, purpose, worktree, model, payload_sha256, reserved, _now()))
            db.execute("commit")
            return Reservation(cursor.lastrowid, approval_id, reserved)
        except BaseException:
            if db.in_transaction:
                db.execute("rollback")
            raise
        finally:
            db.close()

    # ---- 응답 뒤 정산 ----
    def settle(self, reservation: Reservation, status: str, input_tokens: Optional[int] = None, output_tokens: Optional[int] = None,
               reason: Optional[str] = None) -> int:
        """usage가 둘 다 있으면 승인 단가로 실제 추정액을, 없으면 예약액 전체를 확정한다. 확정액(마이크로달러)을 돌려준다."""
        with self._connect() as db:
            in_price, out_price = db.execute("select input_price_micros_per_mtok, output_price_micros_per_mtok from approval where approval_id = ?",
                                             (reservation.approval_id,)).fetchone()
            if input_tokens is not None and output_tokens is not None:
                charged = -(-(input_tokens * in_price + output_tokens * out_price) // 1_000_000)
            else:
                charged = reservation.reserved_micros
            db.execute("update calls set status = ?, charged_micros = ?, input_tokens = ?, output_tokens = ?, reason = ?, settled_at = ? "
                       "where call_id = ? and status = 'RESERVED'",
                       (status, charged, input_tokens, output_tokens, reason, _now(), reservation.call_id))
            return charged

    def summary(self, approval_id: str) -> dict:
        with self._connect() as db:
            approval = db.execute("select max_calls, max_micros, expires_at, purpose from approval where approval_id = ?", (approval_id,)).fetchone()
            calls, committed = db.execute("select count(*), coalesce(sum(coalesce(charged_micros, reserved_micros)), 0) from calls where approval_id = ?",
                                          (approval_id,)).fetchone()
            by_status = dict(db.execute("select status, count(*) from calls where approval_id = ? group by status", (approval_id,)).fetchall())
        if approval is None:
            return {"approval_id": approval_id, "registered": False}
        return {"approval_id": approval_id, "registered": True, "max_calls": approval[0], "max_usd": approval[1] / MICRO, "expires_at": approval[2],
                "purpose": approval[3], "calls": calls, "committed_usd": committed / MICRO, "by_status": by_status}

    @contextlib.contextmanager
    def single_flight(self) -> Iterator[None]:
        """동시 호출 1건. 다른 프로세스가 호출 중이면 기다리지 않고 LLM_BUSY."""
        lock_path = self.path.with_suffix(".lock")
        with lock_path.open("a") as handle:
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise BudgetError("LLM_BUSY", "다른 외부 LLM 호출이 진행 중입니다.") from error
            try:
                yield
            finally:
                fcntl.flock(handle, fcntl.LOCK_UN)


def main(argv: Optional[list[str]] = None) -> int:
    """사람이 실행하는 예산 등록·조회 명령. 등록은 사용자 승인 범위(호출 수·금액·기간·용도)를 그대로 옮긴다."""
    parser = argparse.ArgumentParser(description="외부 LLM 호출 예산 원장")
    sub = parser.add_subparsers(dest="command", required=True)
    grant = sub.add_parser("grant")
    grant.add_argument("--approval-id", required=True)
    grant.add_argument("--max-calls", required=True, type=int)
    grant.add_argument("--max-usd", required=True, type=float)
    grant.add_argument("--input-usd-per-mtok", required=True, type=float, help="보수적 추정 입력 단가(실제 청구 단가 아님)")
    grant.add_argument("--output-usd-per-mtok", required=True, type=float, help="보수적 추정 출력 단가(실제 청구 단가 아님)")
    grant.add_argument("--expires-at", required=True, help="UTC ISO-8601, 예: 2026-10-12T15:00:00+00:00")
    grant.add_argument("--purpose", required=True, choices=["smoke", "eval", "dev"])
    grant.add_argument("--note", required=True)
    show = sub.add_parser("show")
    show.add_argument("--approval-id", required=True)
    args = parser.parse_args(argv)
    ledger = Ledger(ledger_path())
    if args.command == "grant":
        ledger.register(args.approval_id, args.max_calls, args.max_usd, args.input_usd_per_mtok, args.output_usd_per_mtok, args.expires_at, args.purpose, args.note)
    print(json.dumps(ledger.summary(args.approval_id), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
