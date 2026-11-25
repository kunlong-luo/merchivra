#!/usr/bin/env python3
"""Require the isolated V236 service regression to run, not silently skip."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "apps/server/build/test-results/test/TEST-org.merchivra.erp.finance.SettlementReportScanServiceTest.xml"
METHOD = "actualPg18OwnerRestartAndFencedImportRejectStaleAuthorization"


def verify(path, method=METHOD, markers=None):
    result = ET.parse(path).getroot()
    cases = result.findall("testcase")
    if result.tag != "testsuite" or len(cases) != 1:
        raise ValueError("Expected exactly one isolated Reports regression")
    case = cases[0]
    if case.get("name") not in (method, method + "()"):
        raise ValueError("Unexpected Reports regression method")
    if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
        raise ValueError("Reports regression did not execute successfully")
    for key in ("skipped", "failures", "errors"):
        if int(result.get(key, "0")) != 0:
            raise ValueError("Reports regression suite was not successful")
    output="\n".join(node.text or "" for node in result.iter("system-out"))
    for marker in (markers if markers is not None else (
        "PASS V241 actual PG18 shared Reports eligibility row fence and reviewer revocation",
        "PASS V242 actual PG18 report missing currency rejected before staging",
    )):
        if marker not in output:
            raise ValueError(f"Required regression completion marker missing: {marker}")


if __name__ == "__main__":
    try:
        verify(REPORT)
        verify(REPORT.with_name('TEST-org.merchivra.erp.finance.ReceiptCostCompositionPg18V250Test.xml'),
               'actualPg18ReceiptCostsAndSaleRangesConserveAmounts',
               ('PASS V250 actual PG18 V249 composition:',))
    except (OSError, ET.ParseError, ValueError) as exc:
        sys.exit(f"FAIL isolated PG18 service JUnit evidence: {exc}")
    print("PASS V236 actual PG18 Java service test executed (not skipped); remote worker synthetic")
    print("PASS V241/V242 actual PG18 eligibility and currency regression markers verified")

    print("PASS V250 actual PG18 composition JUnit marker verified (not skipped)")
