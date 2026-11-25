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
               ('PASS V250 actual PG18 V249 composition:',
                'PASS V254 actual Owner HTTP nonempty receipt candidate',
                'PASS V255 actual PG18 scoped candidate capture',
                'PASS V256 actual PG18 immutable local cost basis',
                'PASS V257 actual PG18 refund races:',
                'PASS V258 actual PG18 whole-order original cost',
                'PASS V260 actual PG18 historical SKU mapping missing and remapped hold, V259 amount coverage',
                'PASS V261 actual PG18 V63 review required, stale revision, author independence and duplicate audit hold',
                'PASS V262 actual PG18 nonreusable refund evidence revisions, ABA, recreation, no-op and rollback'))
    except (OSError, ET.ParseError, ValueError) as exc:
        sys.exit(f"FAIL isolated PG18 service JUnit evidence: {exc}")
    print("PASS V236 actual PG18 Java service test executed (not skipped); remote worker synthetic")
    print("PASS V241/V242 actual PG18 eligibility and currency regression markers verified")

    print("PASS V250 actual PG18 composition JUnit marker verified (not skipped)")
    print("PASS V254 actual nonempty Owner HTTP JUnit marker verified (not skipped)")
    print("PASS V255 actual PG18 original candidate replay JUnit marker verified (not skipped)")
    print("PASS V256 actual PG18 immutable candidate and refund reservation JUnit marker verified (not skipped)")
    print("PASS V257 actual PG18 refund race and stale read JUnit marker verified (not skipped)")
    print("PASS V258 actual PG18 whole-order cost JUnit marker verified (not skipped)")

    print("PASS V260 actual PG18 V203 mapping and V259 amount coverage JUnit marker verified (not skipped)")

    print("PASS V261 actual PG18 independent refund review and audit JUnit marker verified (not skipped)")

    print("PASS V262 actual PG18 refund evidence revision JUnit marker verified (not skipped)")
