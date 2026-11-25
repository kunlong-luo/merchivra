#!/usr/bin/env python3
"""V228 verify reviewed Orval request/response against actual running Springdoc.

Build-time snapshot can be static; acceptance checks live Controller mapping,
operation ID and schema fields instead of trusting a proposed API route.
No production OAuth/session/finance writes.
"""
import json
import sys
import urllib.request
from pathlib import Path

PATH = "/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/batch"
SNAPSHOT = "apps/web/openapi/manual-cost-v228.json"
expected = json.loads(Path(SNAPSHOT).read_text())
assert expected["paths"][PATH]["post"]["operationId"] == "stageManualCostSourceBatch"
assert expected["paths"][PATH]["post"]["requestBody"]["content"]["application/json"]["schema"]["maxItems"] == 20
draft = expected["components"]["schemas"]["ManualCostDraftV228"]
source = expected["components"]["schemas"]["ManualCostSourceV228"]
fields = {"category", "sourceKind", "sourceKey", "documentSha256",
          "businessDate", "amount", "currency"}
assert set(draft["required"]) == fields
assert set(source["properties"]) >= (fields | {"id", "origin", "state", "marketplaceId"})
assert expected["paths"][PATH]["post"]["security"] == [
    {"sessionCookie": [], "csrfHeader": []}]
try:
    with urllib.request.urlopen(
        "http://127.0.0.1:8080/v3/api-docs", timeout=15) as response:
        live = json.loads(response.read())
except Exception as err:
    raise AssertionError("V228 requires reachable actual Springdoc") from err
operation = live["paths"][PATH]["post"]
assert operation["operationId"] == "stageManualCostSourceBatch"
assert any(p.get("name") == "marketplaceId" and
           p.get("in") == "path" and p.get("required")
           for p in operation["parameters"])
assert "application/json" in operation["requestBody"]["content"]
assert "200" in operation["responses"]
# Springdoc may render request bodies as array of $ref or inline Draft schemas.
def deref(schema):
    while "$ref" in schema:
        parts = schema["$ref"].split("/")
        assert parts[:3] == ["#", "components", "schemas"]
        schema = live["components"]["schemas"][parts[-1]]
    return schema
body = deref(operation["requestBody"]["content"]["application/json"]["schema"])
assert body["type"] == "array" and set(deref(body["items"]).get("properties",{})) >= fields
# Springdoc emits */* for this @PostMapping without an explicit produces.
# Content negotiation still returns JSON to the actual Owner client; enforce
# a real list Source schema instead of assuming the media key spelling.
responses = operation["responses"]["200"].get("content", {})
produces = next((key for key in ("application/json", "*/*",
                                 "application/*+json") if key in responses), None)
assert produces is not None, "V228 missing declared JSON-compatible 200 schema"
result = deref(responses[produces]["schema"])
assert result["type"] == "array", "V228 response must be Source[]"
item_schema = deref(result["items"])
required_fields = {
    "id", "marketplaceId", "sourceKey", "documentSha256", "amount", "currency",
    "origin", "state"}
actual_fields = set(item_schema.get("properties", {}))
assert actual_fields >= required_fields, (
    "V228 real Springdoc response does not describe a complete V91 Source: "
    f"items={result['items']!r}, available={sorted(actual_fields)!r}, "
    f"missing={sorted(required_fields-actual_fields)!r}")
print("PASS V228 actual Springdoc Owner batch POST matches reviewed Orval "
      "operation, input Draft fields, response Source shape, and same-origin "
      "session+CSRF contract; no verified profit")


# V230 extends the SAME reviewed Orval config, not a parallel HTTP
# client: validate the new actual Java operation and namespaced DTO schema.
V230_PATH="/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/external-proof-dossier"
V230_DOSSIER="ExternalCostProofDossierV230"
V230_REQUIREMENT="ExternalCostProofRequirementV230"
assert expected["paths"][V230_PATH]["get"]["operationId"] == (
    "getManualCostExternalProofDossierV230")
assert expected["components"]["schemas"][V230_DOSSIER]["properties"][
    "realizedNetProfit"]["nullable"] is True
expected_fields=set(expected["components"]["schemas"][V230_DOSSIER]["required"])
critical={
    "sourceId","marketplaceId","category","originalCurrency",
    "sourceReviewState","localAuditStatus","localAttributionStatus",
    "localCollisionHeld","localSourceAuditComplete",
    "independentMetadataReviewComplete","localDossierStatus","blockerCodes",
    "requiredExternalProof","issuerIdentityVerified","originalBytesVerified",
    "originalChargeVerified","actualLandedOrRefundCogsVerified",
    "accountingPolicyApproved","eligibleForRealizedProfit","realizedNetProfit"
}
assert expected_fields >= critical
live_get=live["paths"][V230_PATH]["get"]
assert live_get["operationId"]=="getManualCostExternalProofDossierV230"
assert {"marketplaceId","sourceId"} <= {
    p["name"] for p in live_get["parameters"] if p.get("in")=="path"
    and p.get("required")}
live_content=live_get["responses"]["200"]["content"]
live_media=next((key for key in ("application/json","*/*",
    "application/*+json") if key in live_content),None)
assert live_media, "V230 must advertise a JSON-compatible dossier"
live_dossier=deref(live_content[live_media]["schema"])
actual=set(live_dossier.get("properties",{}))
assert actual >= critical, ("V230 real Springdoc source dossier mismatch",
                           sorted(critical-actual),sorted(actual))
live_requirements=deref(live_dossier["properties"]["requiredExternalProof"])
assert live_requirements.get("type")=="array"
live_requirement=deref(live_requirements["items"])
assert {"code","description","independentlyVerified"} <= set(
    live_requirement.get("properties",{}))
print("PASS V230 live Springdoc namespaced Orval read dossier with audited "
      "source, scoped proof requirements, all external truth/profit fields",flush=True)


# V253: verify the existing Orval bundle against actual V252 Springdoc.
V253_PATH="/api/v1/finance/marketplaces/{marketplaceId}/purchase-receipts/{receiptId}/cost-composition-candidate"
declared=expected["paths"][V253_PATH]["get"]
actual=live["paths"][V253_PATH]["get"]
assert declared["operationId"]==actual["operationId"]=="getPurchaseReceiptCostCandidateV252"
assert declared["security"]==[{"sessionCookie":[]}]
assert {"marketplaceId","receiptId"}=={p["name"] for p in actual["parameters"]
    if p.get("in")=="path" and p.get("required")}
def exact_properties(expected_name,live_schema):
    schema=deref(live_schema)
    fields=expected["components"]["schemas"][expected_name]["properties"]
    assert set(schema["properties"])==set(fields), (expected_name,sorted(set(fields)-set(schema["properties"])))
    return schema["properties"]
props=exact_properties("ReceiptCostCandidateV252",actual["responses"]["200"]["content"]["application/json"]["schema"])
for name,contract in (("components","ReceiptCostSourceSummaryV252"),("saleShares","ReceiptCostSaleShareV252")):
    exact_properties(contract,deref(props[name])["items"])
for field in ("receiptCandidateCost","allocatedCandidateCost","unallocatedCandidateCost","actualBatchCogs","realizedNetProfit"):
    assert deref(props[field]).get("type")=="string",field
print("PASS V253 actual Springdoc receipt candidate GET and nested Orval response fields")
