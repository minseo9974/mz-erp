"""Independent re-computation of the scenario answers.

Does NOT import tools/scenario/compute_answers.py. Reads the scenario TOML, rebuilds the trial
balance from scratch, and compares it with the table printed in docs/scenario/answers-2026.md.
Also recomputes the closing amounts from the underlying facts instead of trusting the TOML values.
"""
import re
import sys
import tomllib
from collections import defaultdict
from pathlib import Path

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
sc = tomllib.loads((root / "data/scenario/mz-electronics-2026.toml").read_text(encoding="utf-8"))
answers = (root / "docs/scenario/answers-2026.md").read_text(encoding="utf-8")

problems = []
dr = defaultdict(int)
cr = defaultdict(int)
by_partner = defaultdict(int)  # (account, partner) -> debit - credit


def post(lines, label):
    d = sum(l[2] for l in lines if l[0] == "D")
    c = sum(l[2] for l in lines if l[0] == "C")
    if d != c:
        problems.append(f"{label}: debit {d} != credit {c}")
    for l in lines:
        (dr if l[0] == "D" else cr)[l[1]] += l[2]
        if len(l) > 3:
            by_partner[(l[1], l[3])] += l[2] if l[0] == "D" else -l[2]


post(sc["opening"]["lines"], "opening")
for e in sc["entries"]:
    post(e["lines"], f"entry {e['no']}")
    v = e.get("vat")
    if v:
        expected = v["supply"] // 10 if v["type"] in {"11", "17", "22", "51", "54", "57", "61"} else 0
        if v["tax"] != expected:
            problems.append(f"entry {e['no']}: tax {v['tax']} != {expected}")

# balances before closing, used to recompute the closing amounts from facts
def bal(code):
    return dr[code] - cr[code]

pre = {c: bal(c) for c in set(dr) | set(cr)}
facts = sc["closing_facts"]

def vat_by_half(direction, deductible_only):
    out = {1: 0, 2: 0}
    for e in sc["entries"]:
        v = e.get("vat")
        if not v or v["direction"] != direction:
            continue
        if deductible_only and v["type"] == "54":
            continue
        out[1 if e["date"].month <= 6 else 2] += v["tax"]
    return out

sales_tax = vat_by_half("SALES", False)
purchase_tax = vat_by_half("PURCHASE", True)
raw_purchases = sum(l[2] for e in sc["entries"] for l in e["lines"] if l[0] == "D" and l[1] == "153")
opening = {(l[1], l[0]): 0 for l in sc["opening"]["lines"]}
for l in sc["opening"]["lines"]:
    opening[(l[1], l[0])] += l[2]

expect = {
    1: {("255", "D"): sales_tax[2], ("135", "C"): purchase_tax[2], ("261", "C"): sales_tax[2] - purchase_tax[2]},
    2: {("133", "D"): 1_200_000 * 11 // 12, ("521", "C"): 1_200_000 * 11 // 12},
    3: {("951", "D"): 300_000, ("262", "C"): 300_000},
    4: {("518", "D"): 50_000_000 // 10, ("818", "D"): 6_000_000 // 5 + 2_000_000 // 5 * 6 // 12,
        ("207", "C"): 50_000_000 // 10, ("213", "C"): 6_000_000 // 5 + 2_000_000 // 5 * 6 // 12},
    5: {("835", "D"): pre["108"] // 100 - (-pre["109"]), ("109", "C"): pre["108"] // 100 - (-pre["109"])},
    6: {("501", "D"): opening[("153", "D")] + raw_purchases - facts["ending_raw_material"],
        ("153", "C"): opening[("153", "D")] + raw_purchases - facts["ending_raw_material"]},
}
raw_cost = expect[6][("501", "D")]
labor = pre["504"]
overhead = {"518": 5_000_000, "520": pre["520"], "521": pre["521"] - 1_100_000}
cogm = raw_cost + labor + sum(overhead.values())
cogs = opening[("150", "D")] + cogm - facts["ending_finished_goods"]
prepaid_tax = pre["136"]
expect[7] = {("150", "D"): cogm, ("501", "C"): raw_cost, ("504", "C"): labor,
             ("518", "C"): overhead["518"], ("520", "C"): overhead["520"], ("521", "C"): overhead["521"]}
expect[8] = {("455", "D"): cogs, ("150", "C"): cogs}
expect[9] = {("998", "D"): facts["income_tax"], ("136", "C"): prepaid_tax, ("261", "C"): facts["income_tax"] - prepaid_tax}

# 받을어음 대손충당금: 설정액이 0이어야 결산5에 라인이 없는 것이 맞다
if pre["110"] // 100 != -pre["111"]:
    problems.append(f"110 allowance: need {pre['110'] // 100}, have {-pre['111']}")
# 1기 부가세 정리 전표(#18)도 같은 방식으로 확인
e18 = next(e for e in sc["entries"] if e["no"] == 18)
got18 = {(l[1], l[0]): l[2] for l in e18["lines"]}
want18 = {("255", "D"): sales_tax[1], ("135", "C"): purchase_tax[1], ("261", "C"): sales_tax[1] - purchase_tax[1]}
if got18 != want18:
    problems.append(f"entry 18: {got18} != {want18}")

for c in sc["closing"]:
    got = {(l[1], l[0]): l[2] for l in c["lines"]}
    if got != expect[c["no"]]:
        problems.append(f"closing {c['no']}: toml {got} != recomputed {expect[c['no']]}")
    post(c["lines"], f"closing {c['no']}")

# ---- compare with the trial balance printed in answers-2026.md
rows = re.findall(r"^\| ([\d,]*) \| ([\d,]+) \| (\d{3}) [^|]+ \| ([\d,]+) \| ([\d,]*) \|$", answers, re.M)
num = lambda s: int(s.replace(",", "")) if s else 0
seen = set()
for dbal, dsum, code, csum, cbal in rows:
    seen.add(code)
    mine = (max(bal(code), 0), dr[code], cr[code], max(-bal(code), 0))
    theirs = (num(dbal), num(dsum), num(csum), num(cbal))
    if mine != theirs:
        problems.append(f"TB {code}: recomputed {mine} != answers {theirs}")
for code in (set(dr) | set(cr)) - seen:
    problems.append(f"TB: account {code} missing from answers")

total_dr, total_cr = sum(dr.values()), sum(cr.values())
revenue = -bal("404") - bal("901")
expenses = sum(bal(c) for c in dr.keys() | cr.keys() if c == "455" or c.startswith("8") or c in ("951", "998"))
assets = sum(bal(c) for c in dr.keys() | cr.keys() if c < "250")
liabilities = sum(-bal(c) for c in dr.keys() | cr.keys() if "250" < c < "330")
equity = -bal("331") - bal("375") + revenue - expenses

print("accounts in TB table:", len(rows))
print("TB sum      :", f"{total_dr:,} / {total_cr:,}")
print("TB balances :", f"{sum(max(bal(c), 0) for c in dr.keys() | cr.keys()):,}")
print("COGM / COGS :", f"{cogm:,} / {cogs:,}")
print("revenue - expenses = net income:", f"{revenue:,} - {expenses:,} = {revenue - expenses:,}")
print("assets = liabilities + equity  :", f"{assets:,} = {liabilities:,} + {equity:,}", assets == liabilities + equity)
print("VAT payable 1H / 2H:", f"{sales_tax[1] - purchase_tax[1]:,} / {sales_tax[2] - purchase_tax[2]:,}")
print("partner balances:")
for (acc, p), v in sorted(by_partner.items()):
    if v:
        print(f"  {acc} {p} {abs(v):,}")
for needle in ("6,285,000", "140,166,520", "54,881,520", "85,285,000", "70,900,000", "68,900,000", "3,790,000", "2,600,000"):
    if needle not in answers:
        problems.append(f"answers file lacks {needle}")
print("PROBLEMS:", problems if problems else "none")
sys.exit(1 if problems else 0)
