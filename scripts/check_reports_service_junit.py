#!/usr/bin/env python3
"""Require the isolated V236 service regression to run, not silently skip."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "apps/server/build/test-results/test/TEST-org.merchivra.erp.finance.SettlementReportScanServiceTest.xml"
METHOD = "actualPg18OwnerRestartAndFencedImportRejectStaleAuthorization"


def verify(path):
    result = ET.parse(path).getroot()
    cases = result.findall("testcase")
    if result.tag != "testsuite" or len(cases) != 1:
        raise ValueError("Expected exactly one isolated Reports regression")
    case = cases[0]
    if case.get("name") not in (METHOD, METHOD + "()"):
        raise ValueError("Unexpected Reports regression method")
    if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
        raise ValueError("Reports regression did not execute successfully")
    for key in ("skipped", "failures", "errors"):
        if int(result.get(key, "0")) != 0:
            raise ValueError("Reports regression suite was not successful")


if __name__ == "__main__":
    try:
        verify(REPORT)
    except (OSError, ET.ParseError, ValueError) as exc:
        sys.exit(f"FAIL V236 Reports JUnit evidence: {exc}")
    print("PASS V236 actual PG18 Java service test executed (not skipped); remote worker synthetic")
