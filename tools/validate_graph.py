"""Validate skill-graph JSON files and print each domain's learning order.

Usage:  python3 tools/validate_graph.py content/domains/*.json
Exit code 1 if any file has errors. Standard library only.
"""
import json
import re
import sys
from graphlib import CycleError, TopologicalSorter

ID_RE = re.compile(r"^[a-z0-9-]+$")
KINDS = {"course", "docs", "video", "practice", "book"}
TOP_KEYS = {"id", "version", "title", "description", "goalKeywords", "skills"}
SKILL_KEYS = {"id", "name", "summary", "targetLevel", "estHours", "prereqs", "resources", "quiz"}


def check(graph: dict) -> list[str]:
    errs = []
    for k in ("id", "version", "title", "goalKeywords", "skills"):
        if k not in graph:
            errs.append(f"missing top-level '{k}'")
    errs += [f"unknown top-level key '{k}'" for k in graph.keys() - TOP_KEYS]
    if errs:
        return errs
    if not ID_RE.match(graph["id"]):
        errs.append(f"bad domain id '{graph['id']}'")

    skills = graph["skills"]
    ids = [s.get("id") for s in skills]
    dupes = {i for i in ids if ids.count(i) > 1}
    errs += [f"duplicate skill id '{d}'" for d in sorted(dupes)]
    known = set(ids)

    for s in skills:
        sid = s.get("id", "?")
        for k in ("id", "name", "targetLevel", "estHours", "prereqs", "resources"):
            if k not in s:
                errs.append(f"{sid}: missing '{k}'")
        errs += [f"{sid}: unknown key '{k}'" for k in s.keys() - SKILL_KEYS]
        if not ID_RE.match(str(sid)):
            errs.append(f"{sid}: bad skill id")
        if not 0 <= s.get("targetLevel", -1) <= 1:
            errs.append(f"{sid}: targetLevel must be 0-1")
        if not s.get("estHours", 0) > 0:
            errs.append(f"{sid}: estHours must be > 0")
        for p in s.get("prereqs", []):
            if p not in known:
                errs.append(f"{sid}: prereq '{p}' is not a skill in this domain")
            if p == sid:
                errs.append(f"{sid}: lists itself as a prereq")
        if not s.get("resources"):
            errs.append(f"{sid}: needs at least one resource")
        for r in s.get("resources", []):
            if not str(r.get("url", "")).startswith("https://"):
                errs.append(f"{sid}: resource url must start with https://")
            if r.get("kind") not in KINDS:
                errs.append(f"{sid}: resource kind '{r.get('kind')}' not in {sorted(KINDS)}")
        for n, q in enumerate(s.get("quiz", []), 1):
            opts = q.get("options", [])
            if len(opts) < 2:
                errs.append(f"{sid}: quiz {n} needs 2+ options")
            if not isinstance(q.get("answer"), int) or not 0 <= q["answer"] < len(opts):
                errs.append(f"{sid}: quiz {n} answer index out of range")
    return errs


def learning_order(graph: dict) -> list[str]:
    ts = TopologicalSorter({s["id"]: set(s["prereqs"]) for s in graph["skills"]})
    return list(ts.static_order())


def main(paths: list[str]) -> int:
    if not paths:
        print(__doc__)
        return 2
    failed = False
    for path in paths:
        with open(path, encoding="utf-8") as f:
            graph = json.load(f)
        errs = check(graph)
        if not errs:
            try:
                order = learning_order(graph)
            except CycleError as e:
                errs.append(f"prerequisite cycle: {' -> '.join(e.args[1])}")
        if errs:
            failed = True
            print(f"FAIL {path}")
            for e in errs:
                print(f"  - {e}")
            continue
        hours = sum(s["estHours"] for s in graph["skills"])
        quizzes = sum(len(s.get("quiz", [])) for s in graph["skills"])
        print(f"OK   {path}: {len(order)} skills, {hours:g} h total, {quizzes} quiz questions")
        print(f"     order: {' > '.join(order)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
