"""시나리오 회사 정답 생성기.

data/scenario/*.toml(거래·결산 분개)과 data/seed/accounts.csv(계정 속성)를 읽어
  1) 분개 자체를 검증하고 (차대 일치, 거래처 필수, 부가세 금액, 현금 잔액 음수 여부 …)
  2) 합계잔액시산표, 제조원가명세서, 손익계산서, 이익잉여금처분계산서, 재무상태표,
     부가세 신고서 요약, 거래처별 잔액, 차기이월 기초잔액을 계산해
  3) docs/scenario/answers-2026.md 로 쓴다.

이 스크립트는 MZ-ERP 애플리케이션 코드와 완전히 독립적이다. 앱이 같은 시나리오로 같은 숫자를 내는지가
골든 테스트의 기준이 된다. 결산정리 금액은 사람이 계산해 TOML에 적은 값이고, 여기서는 그 값이
실사 자료(closing_facts)와 맞는지 교차 검증만 한다.

실행:  py tools/scenario/compute_answers.py
"""

from __future__ import annotations

import csv
import json
import sys
import tomllib
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ACCOUNTS_CSV = ROOT / "data" / "seed" / "accounts.csv"
SCENARIO = ROOT / "data" / "scenario" / "mz-electronics-2026.toml"
OUT = ROOT / "docs" / "scenario" / "answers-2026.md"
JOURNAL_OUT = ROOT / "docs" / "scenario" / "journal-2026.md"
# 앱의 골든 테스트(ScenarioGoldenTest)가 읽는 정답. 빌드 때 classpath:scenario/ 로 복사된다
JSON_OUT = ROOT / "data" / "scenario" / "answers-2026.json"

KIND_LABEL = {"OPENING": "기초잔액", "GENERAL": "일반전표", "SALES_PURCHASE": "매입매출전표", "CLOSING": "결산정리"}
VAT_LABEL = {"11": "과세", "12": "영세", "13": "면세", "17": "카과", "22": "현과",
             "51": "과세", "52": "영세", "53": "면세", "54": "불공", "57": "카과", "61": "현과"}

TAXABLE = {"11", "17", "22", "51", "54", "57", "61"}
ZERO_OR_EXEMPT = {"12", "13", "16", "52", "53", "58"}
SALES_TYPES = {"11", "12", "13", "16", "17", "22"}
PURCHASE_TYPES = {"51", "52", "53", "54", "57", "58", "61"}


# --------------------------------------------------------------------------- 모델

@dataclass
class Account:
    code: str
    name: str
    category: str
    subgroup: str
    normal: str  # DEBIT | CREDIT
    contra_of: str | None
    requires_partner: bool
    statement: str
    is_system: bool

    @property
    def label(self) -> str:
        return f"{self.code} {self.name}"


@dataclass
class Line:
    side: str  # D | C
    code: str
    amount: int
    partner: str | None


@dataclass
class Entry:
    label: str  # 예: "#12", "결산3", "기초"
    date: date
    kind: str  # OPENING | GENERAL | SALES_PURCHASE | CLOSING
    subtype: str | None
    desc: str
    lines: list[Line]
    vat: dict | None = None
    order: int = 0

    def debit(self, code: str) -> int:
        return sum(l.amount for l in self.lines if l.side == "D" and l.code == code)

    def credit(self, code: str) -> int:
        return sum(l.amount for l in self.lines if l.side == "C" and l.code == code)


@dataclass
class Ledger:
    accounts: dict[str, Account]
    dr: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    cr: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    pdr: dict[tuple[str, str], int] = field(default_factory=lambda: defaultdict(int))
    pcr: dict[tuple[str, str], int] = field(default_factory=lambda: defaultdict(int))

    def post(self, e: Entry) -> None:
        for l in e.lines:
            if l.side == "D":
                self.dr[l.code] += l.amount
                if l.partner:
                    self.pdr[(l.code, l.partner)] += l.amount
            else:
                self.cr[l.code] += l.amount
                if l.partner:
                    self.pcr[(l.code, l.partner)] += l.amount

    def balance(self, code: str) -> int:
        """정상잔액 방향 기준 잔액 (차감계정은 대변 잔액이 양수)."""
        d, c = self.dr[code], self.cr[code]
        return d - c if self.accounts[code].normal == "DEBIT" else c - d

    def partner_balance(self, code: str, partner: str) -> int:
        d, c = self.pdr[(code, partner)], self.pcr[(code, partner)]
        return d - c if self.accounts[code].normal == "DEBIT" else c - d


# --------------------------------------------------------------------------- 로딩

def load_accounts() -> dict[str, Account]:
    with ACCOUNTS_CSV.open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    return {
        r["code"]: Account(
            code=r["code"],
            name=r["name"],
            category=r["category"],
            subgroup=r["subgroup"],
            normal=r["normal_balance"],
            contra_of=r["contra_of"] or None,
            requires_partner=r["requires_partner"] == "true",
            statement=r["statement"],
            is_system=r["is_system"] == "true",
        )
        for r in rows
    }


def to_lines(raw: list[list]) -> list[Line]:
    out = []
    for item in raw:
        side, code, amount, *rest = item
        out.append(Line(side, str(code), int(amount), rest[0] if rest else None))
    return out


def load_scenario() -> tuple[dict, list[Entry]]:
    with SCENARIO.open("rb") as f:
        s = tomllib.load(f)
    entries: list[Entry] = []
    op = s["opening"]
    entries.append(Entry("기초", op["date"], "OPENING", None, op["desc"], to_lines(op["lines"]), order=0))
    for i, e in enumerate(s["entries"], start=1):
        entries.append(Entry(f"#{e['no']}", e["date"], e["type"], None, e["desc"], to_lines(e["lines"]), e.get("vat"), order=i))
    base = len(entries)
    for i, e in enumerate(s["closing"], start=1):
        entries.append(Entry(f"결산{e['no']}", e["date"], "CLOSING", e["subtype"], e["desc"], to_lines(e["lines"]), order=base + i))
    entries.sort(key=lambda x: (x.date, x.order))
    return s, entries


# --------------------------------------------------------------------------- 검증

def validate(s: dict, entries: list[Entry], accounts: dict[str, Account]) -> list[str]:
    errors: list[str] = []
    partners = {p["code"] for p in s["partners"]}
    start, end = s["company"]["start"], s["company"]["end"]

    for e in entries:
        where = f"[{e.label} {e.date}]"
        if len(e.lines) < 2:
            errors.append(f"{where} 라인이 2줄 미만")
        if not (start <= e.date <= end):
            errors.append(f"{where} 회계기간 밖의 날짜")
        if e.kind == "CLOSING" and e.date != end:
            errors.append(f"{where} 결산 전표는 기말일이어야 함")
        d_sum = c_sum = 0
        for l in e.lines:
            acct = accounts.get(l.code)
            if acct is None:
                errors.append(f"{where} 없는 계정 {l.code}")
                continue
            if l.side not in ("D", "C"):
                errors.append(f"{where} 차대 구분 오류 {l.side}")
            if l.amount <= 0:
                errors.append(f"{where} {acct.label} 금액이 0 이하")
            if acct.is_system:
                errors.append(f"{where} 시스템 계정 {acct.label} 사용 불가")
            if acct.requires_partner and not l.partner:
                errors.append(f"{where} {acct.label}은 거래처 필수")
            if l.partner and l.partner not in partners:
                errors.append(f"{where} 없는 거래처 {l.partner}")
            if e.kind == "OPENING" and acct.category in ("REVENUE", "EXPENSE"):
                errors.append(f"{where} 기초잔액에 손익 계정 {acct.label}")
            if l.side == "D":
                d_sum += l.amount
            else:
                c_sum += l.amount
        if d_sum != c_sum:
            errors.append(f"{where} 차대 불일치: 차변 {d_sum:,} / 대변 {c_sum:,}")

        # 부가세
        if e.kind == "SALES_PURCHASE":
            v = e.vat
            if not v:
                errors.append(f"{where} 매입매출전표에 부가세 정보 없음")
                continue
            t, supply, tax = v["type"], int(v["supply"]), int(v["tax"])
            if v["partner"] not in partners:
                errors.append(f"{where} 부가세 거래처 {v['partner']} 없음")
            if (v["direction"] == "SALES") != (t in SALES_TYPES):
                errors.append(f"{where} 유형 {t}과 방향 {v['direction']} 불일치")
            if t in TAXABLE and tax != supply * 10 // 100:
                errors.append(f"{where} 과세 세액 {tax:,} ≠ 공급가액 10% {supply * 10 // 100:,}")
            if t in ZERO_OR_EXEMPT and tax != 0:
                errors.append(f"{where} 영세·면세인데 세액 {tax:,}")
            if t in {"11", "17", "22"} and e.credit("255") != tax:
                errors.append(f"{where} 부가세예수금 {e.credit('255'):,} ≠ 세액 {tax:,}")
            if t in {"51", "57", "61"} and e.debit("135") != tax:
                errors.append(f"{where} 부가세대급금 {e.debit('135'):,} ≠ 세액 {tax:,}")
            if t == "54" and e.debit("135") != 0:
                errors.append(f"{where} 불공제인데 부가세대급금 계상")
            if t in SALES_TYPES:
                revenue = sum(l.amount for l in e.lines if l.side == "C" and accounts[l.code].subgroup == "매출")
                if revenue != supply:
                    errors.append(f"{where} 매출액 {revenue:,} ≠ 공급가액 {supply:,}")
        elif e.vat:
            errors.append(f"{where} 일반전표에 부가세 정보가 있음")

    # 현금·보통예금 잔액이 음수로 내려가는지
    running = defaultdict(int)
    for e in entries:
        for l in e.lines:
            if l.code in ("101", "103"):
                running[l.code] += l.amount if l.side == "D" else -l.amount
        for code in ("101", "103"):
            if running[code] < 0:
                errors.append(f"[{e.label} {e.date}] {accounts[code].label} 잔액 음수 {running[code]:,}")
    return errors


# --------------------------------------------------------------------------- 출력 도우미

def won(n: int) -> str:
    return f"({abs(n):,})" if n < 0 else f"{n:,}"


def table(headers: list[str], rows: list[list], right: set[int] | None = None) -> str:
    right = right or set()
    out = ["| " + " | ".join(headers) + " |",
           "| " + " | ".join("---:" if i in right else "---" for i in range(len(headers))) + " |"]
    for r in rows:
        out.append("| " + " | ".join(str(c) for c in r) + " |")
    return "\n".join(out)


def render_journal(entries: list[Entry], accounts: dict[str, Account], partners: dict[str, str]) -> str:
    """전표별 차변·대변을 사람이 읽기 좋은 분개장으로."""
    out = ["# 분개장 — (주)엠제트전자 제6기 (2026)\n",
           "> `py tools/scenario/compute_answers.py`가 생성합니다. 원본은 `data/scenario/mz-electronics-2026.toml`.\n",
           "공부할 때는 각 전표의 적요만 보고 직접 분개해 본 뒤 표와 비교해 보세요.\n"]
    for e in entries:
        head = f"### {e.label} · {e.date.isoformat()} · {KIND_LABEL[e.kind]}"
        if e.vat:
            head += f" ({e.vat['type']} {VAT_LABEL.get(e.vat['type'], '')})"
        if e.subtype:
            head += f" · {e.subtype}"
        out.append(head + "\n")
        out.append(f"{e.desc}\n")
        if e.vat:
            v = e.vat
            extra = " · 고정자산" if v.get("fixed_asset") else ""
            extra += f" · 불공제 사유: {v['non_deductible_reason']}" if v.get("non_deductible_reason") else ""
            out.append(f"부가세: {partners[v['partner']]} · 공급가액 {int(v['supply']):,} · 세액 {int(v['tax']):,}{extra}\n")
        debits = [l for l in e.lines if l.side == "D"]
        credits = [l for l in e.lines if l.side == "C"]
        rows = []
        for i in range(max(len(debits), len(credits))):
            row = []
            for side in (debits, credits):
                if i < len(side):
                    l = side[i]
                    name = accounts[l.code].label + (f" ({partners[l.partner]})" if l.partner else "")
                    row += [name, f"{l.amount:,}"]
                else:
                    row += ["", ""]
            rows.append(row)
        out.append(table(["차변", "금액", "대변", "금액"], rows, {1, 3}))
        out.append("")
    return "\n".join(out)


# --------------------------------------------------------------------------- 계산

def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글·기호 출력
    accounts = load_accounts()
    s, entries = load_scenario()
    errors = validate(s, entries, accounts)
    if errors:
        print("시나리오 검증 실패:")
        for err in errors:
            print("  -", err)
        return 1

    facts = s["closing_facts"]
    partners = {p["code"]: p["name"] for p in s["partners"]}
    opening = next(e for e in entries if e.kind == "OPENING")
    mfg_transfer = next(e for e in entries if e.subtype == "MFG_TRANSFER")

    full = Ledger(accounts)  # 결산 후 (손익 대체 전)
    for e in entries:
        full.post(e)
    pre_transfer = Ledger(accounts)  # 제조원가명세서용: 제조원가 대체 전표 제외
    for e in entries:
        if e is not mfg_transfer:
            pre_transfer.post(e)

    checks: list[str] = []

    def check(cond: bool, msg: str) -> None:
        if not cond:
            raise SystemExit(f"정답 검증 실패: {msg}")
        checks.append(msg)

    used = sorted(c for c in accounts if full.dr[c] or full.cr[c])
    md: list[str] = []
    md.append("# 시나리오 정답 — (주)엠제트전자 제6기 (2026-01-01 ~ 2026-12-31)\n")
    md.append("> 이 파일은 `py tools/scenario/compute_answers.py`가 생성합니다. 직접 고치지 말고 "
              "`data/scenario/mz-electronics-2026.toml`을 고친 뒤 다시 실행하세요.\n")
    md.append(f"전표 {sum(1 for e in entries if e.kind in ('GENERAL', 'SALES_PURCHASE'))}장 · "
              f"결산정리 {sum(1 for e in entries if e.kind == 'CLOSING')}장 · 기초잔액 1장\n")

    # ---------------- 합계잔액시산표
    rows = []
    t_dr = t_cr = t_bd = t_bc = 0
    for c in used:
        d, cr_ = full.dr[c], full.cr[c]
        bd, bc = (d - cr_, 0) if d >= cr_ else (0, cr_ - d)
        t_dr, t_cr, t_bd, t_bc = t_dr + d, t_cr + cr_, t_bd + bd, t_bc + bc
        rows.append([won(bd) if bd else "", won(d), accounts[c].label, won(cr_), won(bc) if bc else ""])
    rows.append([f"**{won(t_bd)}**", f"**{won(t_dr)}**", "**합계**", f"**{won(t_cr)}**", f"**{won(t_bc)}**"])
    check(t_dr == t_cr, f"시산표 차변합계 = 대변합계 ({t_dr:,})")
    check(t_bd == t_bc, f"시산표 차변잔액 = 대변잔액 ({t_bd:,})")
    md.append("## 1. 합계잔액시산표 (결산정리 후, 손익 대체 전)\n")
    md.append(table(["차변 잔액", "차변 합계", "계정과목", "대변 합계", "대변 잔액"], rows, {0, 1, 3, 4}))
    md.append("")

    # ---------------- 제조원가명세서
    mc_codes = [c for c in used if accounts[c].statement == "MC"]
    mc = {c: pre_transfer.balance(c) for c in mc_codes}
    raw_begin = opening.debit("153")
    raw_purchase = sum(e.debit("153") for e in entries if e.kind in ("GENERAL", "SALES_PURCHASE"))
    raw_end = facts["ending_raw_material"]
    check(raw_begin + raw_purchase - raw_end == mc.get("501", 0),
          f"원재료비 = 기초 {raw_begin:,} + 매입 {raw_purchase:,} − 기말 {raw_end:,} = {mc.get('501', 0):,}")
    check(full.balance("153") == raw_end, f"원재료 장부잔액 = 기말 실사 {raw_end:,}")
    labor = [c for c in mc_codes if c in ("504", "505", "508")]
    overhead = [c for c in mc_codes if c not in labor and c != "501"]
    total_mfg = sum(mc.values())
    wip_begin, wip_end = opening.debit("169"), facts["ending_wip"]
    cogm = total_mfg + wip_begin - wip_end
    check(cogm == mfg_transfer.debit("150"), f"당기제품제조원가 {cogm:,} = 제품 대체액")
    rows = [["Ⅰ. 원재료비", "", won(mc.get("501", 0))],
            ["　기초원재료재고액", won(raw_begin), ""],
            ["　당기원재료매입액", won(raw_purchase), ""],
            ["　기말원재료재고액", won(-raw_end), ""],
            ["Ⅱ. 노무비", "", won(sum(mc[c] for c in labor))]]
    rows += [[f"　{accounts[c].label}", won(mc[c]), ""] for c in labor]
    rows.append(["Ⅲ. 경비", "", won(sum(mc[c] for c in overhead))])
    rows += [[f"　{accounts[c].label}", won(mc[c]), ""] for c in overhead]
    rows += [["Ⅳ. 당기총제조원가", "", won(total_mfg)],
             ["Ⅴ. 기초재공품재고액", "", won(wip_begin)],
             ["Ⅵ. 기말재공품재고액", "", won(-wip_end)],
             ["**Ⅶ. 당기제품제조원가**", "", f"**{won(cogm)}**"]]
    md.append("## 2. 제조원가명세서\n")
    md.append("제조원가 계정은 결산7(제품 대체)에서 0이 되므로, 그 전표를 제외한 발생액으로 작성합니다.\n")
    md.append(table(["과목", "금액", "합계"], rows, {1, 2}))
    md.append("")

    # ---------------- 손익계산서
    def group(sub: str) -> list[str]:
        return [c for c in used if accounts[c].statement == "IS" and accounts[c].subgroup == sub]

    sales = sum(full.balance(c) for c in group("매출"))
    fg_begin, fg_end = opening.debit("150"), facts["ending_finished_goods"]
    cogs = fg_begin + cogm - fg_end
    check(cogs == full.balance("455"), f"제품매출원가 = 기초제품 {fg_begin:,} + 제조원가 {cogm:,} − 기말 {fg_end:,} = {cogs:,}")
    check(full.balance("150") == fg_end, f"제품 장부잔액 = 기말 실사 {fg_end:,}")
    gross = sales - cogs
    sga = group("판매비와관리비")
    sga_total = sum(full.balance(c) for c in sga)
    op_income = gross - sga_total
    noi, noe = group("영업외수익"), group("영업외비용")
    noi_total = sum(full.balance(c) for c in noi)
    noe_total = sum(full.balance(c) for c in noe)
    pretax = op_income + noi_total - noe_total
    tax = full.balance("998")
    check(tax == facts["income_tax"], f"법인세등 = {tax:,}")
    net_income = pretax - tax
    rev_total = sum(full.balance(c) for c in used if accounts[c].category == "REVENUE")
    exp_total = sum(full.balance(c) for c in used if accounts[c].category == "EXPENSE" and accounts[c].statement == "IS")
    check(rev_total - exp_total == net_income, f"당기순이익 = 수익 {rev_total:,} − 비용 {exp_total:,} = {net_income:,}")

    rows = [["Ⅰ. 매출액", "", won(sales)]]
    rows += [[f"　{accounts[c].label}", won(full.balance(c)), ""] for c in group("매출")]
    rows += [["Ⅱ. 매출원가", "", won(cogs)],
             ["　기초제품재고액", won(fg_begin), ""],
             ["　당기제품제조원가", won(cogm), ""],
             ["　기말제품재고액", won(-fg_end), ""],
             ["Ⅲ. 매출총이익", "", won(gross)],
             ["Ⅳ. 판매비와관리비", "", won(sga_total)]]
    rows += [[f"　{accounts[c].label}", won(full.balance(c)), ""] for c in sga]
    rows += [["Ⅴ. 영업이익", "", won(op_income)],
             ["Ⅵ. 영업외수익", "", won(noi_total)]]
    rows += [[f"　{accounts[c].label}", won(full.balance(c)), ""] for c in noi]
    rows.append(["Ⅶ. 영업외비용", "", won(noe_total)])
    rows += [[f"　{accounts[c].label}", won(full.balance(c)), ""] for c in noe]
    rows += [["Ⅷ. 법인세차감전순이익", "", won(pretax)],
             ["Ⅸ. 법인세등", "", won(tax)],
             ["**Ⅹ. 당기순이익**", "", f"**{won(net_income)}**"]]
    md.append("## 3. 손익계산서\n")
    md.append(table(["과목", "금액", "합계"], rows, {1, 2}))
    md.append("")

    # ---------------- 이익잉여금처분계산서
    re_begin = full.balance("375") + full.balance("377")
    unappropriated = re_begin + net_income
    md.append("## 4. 이익잉여금처분계산서\n")
    md.append("시나리오에서는 배당·적립 등 처분이 없다고 가정합니다.\n")
    md.append(table(["과목", "금액"], [
        ["Ⅰ. 미처분이익잉여금", won(unappropriated)],
        ["　전기이월미처분이익잉여금", won(re_begin)],
        ["　당기순이익", won(net_income)],
        ["Ⅱ. 이익잉여금처분액", "0"],
        ["**Ⅲ. 차기이월미처분이익잉여금**", f"**{won(unappropriated)}**"],
    ], {1}))
    md.append("")

    # ---------------- 재무상태표
    def bs_section(subgroups: list[str], show_sub: bool = True) -> tuple[list[list], int]:
        out, total = [], 0
        for sub in subgroups:
            codes = [c for c in used if accounts[c].statement == "BS" and accounts[c].subgroup == sub
                     and not accounts[c].contra_of and accounts[c].category != "EQUITY"]
            sub_rows, sub_total = [], 0
            for c in codes:
                b = full.balance(c)
                contras = [k for k in used if accounts[k].contra_of == c]
                if b == 0 and not any(full.balance(k) for k in contras):
                    continue
                sub_rows.append([f"　{accounts[c].label}", won(b), ""])
                sub_total += b
                for k in contras:
                    kb = full.balance(k)
                    if kb:
                        sub_rows.append([f"　　{accounts[k].label}", won(-kb), ""])
                        sub_total -= kb
            if sub_rows:
                if show_sub:
                    out.append([f"({sub})", "", won(sub_total)])
                out += sub_rows
                total += sub_total
        return out, total

    cur_a, cur_a_total = bs_section(["당좌자산", "재고자산"])
    non_a, non_a_total = bs_section(["투자자산", "유형자산", "무형자산", "기타비유동자산"])
    cur_l, cur_l_total = bs_section(["유동부채"], show_sub=False)
    non_l, non_l_total = bs_section(["비유동부채"], show_sub=False)
    assets = cur_a_total + non_a_total
    liabilities = cur_l_total + non_l_total
    eq_rows, eq_total = [], 0
    for c in used:
        a = accounts[c]
        if a.category == "EQUITY" and a.statement == "BS" and a.subgroup != "이익잉여금" and full.balance(c):
            eq_rows.append([f"　{a.label}", won(full.balance(c)), ""])
            eq_total += full.balance(c)
    eq_rows.append([f"　미처분이익잉여금 (당기순이익 {won(net_income)})", won(unappropriated), ""])
    eq_total += unappropriated
    check(assets == liabilities + eq_total,
          f"자산 {assets:,} = 부채 {liabilities:,} + 자본 {eq_total:,}")

    rows = [["**자산**", "", ""], ["Ⅰ. 유동자산", "", won(cur_a_total)]] + cur_a
    rows += [["Ⅱ. 비유동자산", "", won(non_a_total)]] + non_a
    rows += [["**자산총계**", "", f"**{won(assets)}**"], ["**부채**", "", ""],
             ["Ⅰ. 유동부채", "", won(cur_l_total)]] + cur_l
    rows += [["Ⅱ. 비유동부채", "", won(non_l_total)]] + non_l
    rows += [["**부채총계**", "", f"**{won(liabilities)}**"], ["**자본**", "", ""]] + eq_rows
    rows += [["**자본총계**", "", f"**{won(eq_total)}**"],
             ["**부채와자본총계**", "", f"**{won(liabilities + eq_total)}**"]]
    md.append("## 5. 재무상태표 (2026-12-31)\n")
    md.append(table(["과목", "금액", "합계"], rows, {1, 2}))
    md.append("")

    # ---------------- 부가세 신고서 요약
    md.append("## 6. 부가가치세 신고서 요약\n")
    md.append("예정신고는 생략하고 과세기간 단위로 집계합니다. 금액 단위: 원.\n")
    vat_rows = []
    for term, months in ((1, range(1, 7)), (2, range(7, 13))):
        docs = [e for e in entries if e.kind == "SALES_PURCHASE" and e.date.month in months]

        def agg(types: set[str], asset: bool | None = None) -> tuple[int, int]:
            sel = [e.vat for e in docs if e.vat["type"] in types
                   and (asset is None or bool(e.vat.get("fixed_asset")) == asset)]
            return sum(int(v["supply"]) for v in sel), sum(int(v["tax"]) for v in sel)

        s11, t11 = agg({"11"})
        s17, t17 = agg({"17", "22"})
        s12, _ = agg({"12"})
        out_tax = t11 + t17
        p_gen_s, p_gen_t = agg({"51", "54"}, asset=False)
        p_fa_s, p_fa_t = agg({"51"}, asset=True)
        p_card_s, p_card_t = agg({"57", "61"})
        p_nd_s, p_nd_t = agg({"54"})
        p_ex_s, _ = agg({"53"})
        deductible = p_gen_t + p_fa_t + p_card_t - p_nd_t
        payable = out_tax - deductible
        settle = [e for e in entries if e.date.month in months and e.debit("255") > 0]
        check(len(settle) == 1 and settle[0].credit("261") == payable,
              f"{term}기 납부세액 {payable:,} = 부가세 정리 전표의 미지급세금")
        vat_rows += [
            [f"**{term}기**", "", "", ""],
            ["매출 · 과세 세금계산서 발급분", won(s11), won(t11), "유형 11"],
            ["매출 · 과세 신용카드·현금영수증", won(s17), won(t17), "유형 17, 22"],
            ["매출 · 영세율 세금계산서 발급분", won(s12), "0", "유형 12"],
            ["**매출세액 계**", won(s11 + s17 + s12), f"**{won(out_tax)}**", ""],
            ["매입 · 세금계산서 수취분 일반매입", won(p_gen_s), won(p_gen_t), "유형 51(고정자산 외) + 54"],
            ["매입 · 세금계산서 수취분 고정자산매입", won(p_fa_s), won(p_fa_t), "유형 51 고정자산"],
            ["매입 · 신용카드 매입 공제분", won(p_card_s), won(p_card_t), "유형 57, 61"],
            ["매입 · 공제받지 못할 매입세액", won(p_nd_s), won(-p_nd_t), "유형 54"],
            ["**공제 매입세액 계**", "", f"**{won(deductible)}**", ""],
            ["**납부(환급)세액**", "", f"**{won(payable)}**", ""],
            ["참고 · 계산서 수취 (면세)", won(p_ex_s), "", "유형 53, 신고서 밖"],
        ]
    md.append(table(["구분", "공급가액", "세액", "비고"], vat_rows, {1, 2}))
    md.append("")

    # ---------------- 거래처별 잔액
    rows = []
    for c in ("108", "110", "232", "251", "252", "253", "260", "293"):
        for p in sorted({p for (code, p) in list(full.pdr) + list(full.pcr) if code == c}):
            b = full.partner_balance(c, p)
            if b:
                rows.append([accounts[c].label, f"{p} {partners[p]}", won(b)])
        partner_sum = sum(full.partner_balance(c, p) for p in partners)
        check(partner_sum == full.balance(c), f"{accounts[c].label} 거래처별 합계 = 계정 잔액 {full.balance(c):,}")
    md.append("## 7. 거래처별 잔액 (2026-12-31)\n")
    md.append(table(["계정", "거래처", "잔액"], rows, {2}))
    md.append("")

    # ---------------- 차기이월
    rows, o_dr, o_cr = [], 0, 0
    for c in used:
        a = accounts[c]
        if a.statement != "BS" or a.code == "377":
            continue
        b = full.balance(c) + (unappropriated - full.balance("375") if c == "375" else 0)
        if not b:
            continue
        on_debit = (a.normal == "DEBIT") == (b > 0)
        amt = abs(b)
        o_dr += amt if on_debit else 0
        o_cr += 0 if on_debit else amt
        rows.append([a.label, won(amt) if on_debit else "", "" if on_debit else won(amt)])
    rows.append(["**합계**", f"**{won(o_dr)}**", f"**{won(o_cr)}**"])
    check(o_dr == o_cr, f"차기이월 기초잔액 전표 차대 일치 ({o_dr:,})")
    md.append("## 8. 차기이월 — 2027-01-01 기초잔액 전표 (OPENING)\n")
    md.append("연도 마감 후 수익·비용은 0이 되고, 당기순이익은 이월이익잉여금으로 넘어갑니다.\n")
    md.append(table(["계정", "차변", "대변"], rows, {1, 2}))
    md.append("")

    # ---------------- 검증 목록
    md.append("## 9. 자동 검증 결과\n")
    md.append("시나리오 분개 검증(차대 일치, 거래처 필수, 부가세 세액·계정, 기간, 현금 잔액 음수 없음) 통과 후 아래 항목을 확인했습니다.\n")
    md += [f"- [x] {c}" for c in checks]
    md.append("")

    OUT.write_text("\n".join(md), encoding="utf-8")
    JOURNAL_OUT.write_text(render_journal(entries, accounts, partners), encoding="utf-8")
    pairs = sorted(set(full.pdr) | set(full.pcr))
    JSON_OUT.write_text(json.dumps({
        "entries": {"opening": 1,
                    "transactions": sum(1 for e in entries if e.kind in ("GENERAL", "SALES_PURCHASE")),
                    "closing": sum(1 for e in entries if e.kind == "CLOSING")},
        "trialBalance": [{"code": c, "debit": full.dr[c], "credit": full.cr[c]} for c in used],
        "partnerBalances": [{"account": c, "partner": p, "debit": full.pdr[(c, p)], "credit": full.pcr[(c, p)]}
                            for (c, p) in pairs if full.pdr[(c, p)] or full.pcr[(c, p)]],
        "totals": {"sales": sales, "costOfGoodsSold": cogs, "operatingIncome": op_income, "netIncome": net_income,
                   "assets": assets, "liabilities": liabilities, "equity": eq_total},
    }, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    print(f"OK — {OUT.relative_to(ROOT)}, {JOURNAL_OUT.relative_to(ROOT)} 생성, 검증 {len(checks)}건 통과")
    print(f"매출 {sales:,} / 매출원가 {cogs:,} / 영업이익 {op_income:,} / 당기순이익 {net_income:,}")
    print(f"자산 {assets:,} / 부채 {liabilities:,} / 자본 {eq_total:,}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
