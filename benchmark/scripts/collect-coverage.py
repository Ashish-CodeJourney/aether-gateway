#!/usr/bin/env python3
"""AC9 (PRD section 4.1): reads each core module's JaCoCo merged report
(unit + integration test execution data) and writes
benchmark/results/ac9-coverage.json. Run after
`./gradlew :<module>:jacocoMergedReport` for each module below."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
import json

RESULTS_DIR = sys.argv[1] if len(sys.argv) > 1 else "benchmark/results"
REPO_ROOT = Path(__file__).resolve().parents[2]
CORE_MODULES = ["gateway-core", "gateway-router", "gateway-quota", "gateway-cache"]


def line_coverage(module):
    path = REPO_ROOT / "aether-gateway" / module / "build" / "reports" / "jacoco" / "jacocoMergedReport" / "jacocoMergedReport.xml"
    tree = ET.parse(path)
    root = tree.getroot()
    for counter in root.findall("counter"):
        if counter.get("type") == "LINE":
            covered = int(counter.get("covered"))
            missed = int(counter.get("missed"))
            total = covered + missed
            return covered, total
    raise ValueError(f"No LINE counter found in {path}")


def main():
    result = {}
    total_covered = 0
    total_lines = 0
    for module in CORE_MODULES:
        covered, total = line_coverage(module)
        result[module] = {"covered": covered, "total": total, "percent": round(covered / total * 100, 1)}
        total_covered += covered
        total_lines += total
    result["aggregate"] = {
        "covered": total_covered,
        "total": total_lines,
        "percent": round(total_covered / total_lines * 100, 1),
    }

    out_path = Path(RESULTS_DIR) / "ac9-coverage.json"
    with open(out_path, "w") as f:
        json.dump(result, f, indent=2)
    print(f"Wrote {out_path}")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
