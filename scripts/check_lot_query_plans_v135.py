#!/usr/bin/env python3
"""V135: bounded, reproducible PostgreSQL 18 query-plan gate for V132–V134 lots.

Run ONLY after synthetic V95/V132/V133/V134 full-suite HTTP fixtures, against
CI-owned disposable PG18. Inserts 12,000 positions and 6,000 nonposting
COUNT_OBSERVATION actions INSIDE ONE TRANSACTION; EXPLAIN ANALYZE BUFFERS
reads them; ROLLBACK removes every added row (including on process failure).

No live seller, stock, money, private attachments or production credentials.
Measured CI timing is a regression alarm, NOT a production latency SLA.
"""
import json
import os
import subprocess
import sys
from uuid import UUID

POSITION_COUNT = 12000
ACTION_COUNT = 6000
# A generous CI regression guard, not a promised live-tenant p95.
PAGE_BUDGET_MS = 1500.0
AGGREGATE_BUDGET_MS = 3500.0
LARGE_TABLES = {"erp_local_lot_position", "erp_local_lot_action"}
EXPECTED_INDEXES = {
    "position_start": {"erp_local_lot_position_warehouse_recent_idx"},
    "position_deep": {"erp_local_lot_position_warehouse_recent_idx"},
    "history_start": {"erp_local_lot_action_source_recent_idx",
                      "erp_local_lot_action_destination_recent_idx"},
    "history_deep": {"erp_local_lot_action_source_recent_idx",
                     "erp_local_lot_action_destination_recent_idx"},
    "expiry_start": {"erp_local_lot_position_expiry_page_idx"},
    "expiry_deep": {"erp_local_lot_position_expiry_page_idx"},
}
MARKER_PREFIX = "__V135_PLAN__"
PLAN_NAMES = (*EXPECTED_INDEXES, "receipt_allocation")


def parse_explain_output(raw):
    """Reject dropped/truncated/duplicate plans, not just false-positive logs."""
    result = {}
    current = None
    lines = []
    for value in raw.splitlines():
        line = value.strip()
        if line.startswith(MARKER_PREFIX):
            if current is not None:
                raise AssertionError("EXPLAIN marker overlapped")
            current = line[len(MARKER_PREFIX):]
            if current not in PLAN_NAMES or current in result:
                raise AssertionError("Unexpected or duplicate EXPLAIN marker")
            lines = []
        elif line == "__V135_END__":
            if current is None:
                raise AssertionError("EXPLAIN end without start")
            document = json.loads("\n".join(lines))
            assert isinstance(document, list) and len(document) == 1, current
            result[current] = document[0]
            current = None
        elif current is not None:
            lines.append(value)
    if current is not None or set(result) != set(PLAN_NAMES):
        raise AssertionError(f"Missing/unterminated plans: {set(PLAN_NAMES)-set(result)}")
    return result


def nodes(root):
    yield root
    for subplan in root.get("Plans", []):
        yield from nodes(subplan)


def assess(plan_name, plan):
    root = plan["Plan"]
    time_ms = float(plan["Execution Time"])
    actual = int(root.get("Actual Rows", -1))
    indices = {n.get("Index Name") for n in nodes(root) if n.get("Index Name")}
    seq = {n.get("Relation Name") for n in nodes(root)
           if n.get("Node Type") in ("Seq Scan", "Parallel Seq Scan")
           and n.get("Relation Name") in LARGE_TABLES}
    buffer_hits = sum(int(n.get("Shared Hit Blocks", 0)) for n in (root,))
    buffer_reads = sum(int(n.get("Shared Read Blocks", 0)) for n in (root,))
    # Report only execution-plan structure, no fixture IDs or data. This must
    # be emitted BEFORE assertions so a failed A3 is actionable on first run.
    print("V135 PLAN PRECHECK "+json.dumps({
        "probe":plan_name,"node":root.get("Node Type"),"rows":actual,
        "execution_ms":round(time_ms,3),"indexes":sorted(indices),
        "large_table_seq_scans":sorted(seq),"shared_read":buffer_reads,
        "shared_hit":buffer_hits
    },sort_keys=True,separators=(",",":")),flush=True)
    assert actual == (1 if plan_name == "receipt_allocation" else 51), (
        plan_name, "missing or unbounded result", actual)
    if plan_name in EXPECTED_INDEXES:
        assert indices.intersection(EXPECTED_INDEXES[plan_name]), (
            plan_name, "large-tenant access index was not chosen", sorted(indices))
        assert not seq, (plan_name, "unbounded table scan", sorted(seq))
    budget = AGGREGATE_BUDGET_MS if plan_name == "receipt_allocation" else PAGE_BUDGET_MS
    assert time_ms < budget, (plan_name, "execution-time regression", time_ms, budget)
    return dict(name=plan_name, actual_rows=actual, execution_ms=round(time_ms, 3),
                shared_hit=buffer_hits, shared_read=buffer_reads,
                index_names=sorted(indices), sequential_large_tables=sorted(seq))


def self_test():
    sample = {"Plan":{"Node Type":"Limit","Actual Rows":51,"Shared Hit Blocks":8,
                      "Plans":[{"Node Type":"Index Scan",
                                "Index Name":"erp_local_lot_position_warehouse_recent_idx",
                                "Relation Name":"erp_local_lot_position"}]},
              "Execution Time":1.2}
    check = assess("position_start", sample)
    assert check["actual_rows"] == 51 and check["shared_hit"] == 8
    raw = "".join(f"{MARKER_PREFIX}{p}\n{json.dumps([sample])}\n__V135_END__\n"
                  for p in PLAN_NAMES)
    parsed = parse_explain_output(raw)
    assert set(parsed) == set(PLAN_NAMES)
    for bad in (raw.replace(f"{MARKER_PREFIX}history_start\n", ""),
                raw + f"{MARKER_PREFIX}position_start\n"):
        try:
            parse_explain_output(bad)
        except (AssertionError, ValueError):
            pass
        else:
            raise AssertionError("Corrupt EXPLAIN output was accepted")
    try:
        assess("position_start", {"Plan":{"Actual Rows":51,"Node Type":"Seq Scan",
                                          "Relation Name":"erp_local_lot_position"},
                                  "Execution Time":0.1})
    except AssertionError:
        pass
    else:
        raise AssertionError("Missing index not rejected")
    print("PASS V135 local EXPLAIN JSON parser and access-path regression guards")


def psql(text, *, time_limit=120):
    env = {**os.environ, "PGPASSWORD":os.environ["DB_PASSWORD"]}
    command = ["psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
               "-h", "127.0.0.1", "-U", "merchivra", "-d", "merchivra"]
    done = subprocess.run(command, input=text, text=True, capture_output=True,
                          env=env, timeout=time_limit, check=False)
    if done.returncode != 0:
        # Never emit credentials or raw full SQL containing fixture identifiers.
        raise AssertionError("Disposable PG18 V135 script failed: " +
                             done.stderr[-1000:])
    return done.stdout.strip()


def require_disposable():
    assert os.getenv("MERCHIVRA_V135_DISPOSABLE_ONLY") == "true"
    assert os.getenv("GITHUB_ACTIONS") == "true"
    assert os.getenv("DB_HOST") == "127.0.0.1"
    assert os.getenv("DB_NAME") == "merchivra"
    assert os.getenv("DB_USER") == "merchivra"
    assert os.getenv("DB_PASSWORD") == "ci_disposable_database_password"
    assert os.getenv("MERCHIVRA_DEMO_ENABLED") == "true"
    assert os.getenv("MERCHIVRA_SPAPI_ENABLED") == "false"
    assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED") == "false"
    assert os.getenv("MERCHIVRA_SPAPI_ALLOW_WRITES", "false") == "false"
    assert os.getenv("MERCHIVRA_BOOTSTRAP_USERNAME") == "v65-ci-owner"


def checked_uuid(value):
    return str(UUID(value))


def benchmark():
    require_disposable()
    pg_version = psql("SHOW server_version_num;")
    assert 180000 <= int(pg_version) < 190000, (
        "This benchmark intentionally requires PostgreSQL 18", pg_version)
    names = {x for x in psql("""
        SELECT indexname FROM pg_indexes
        WHERE schemaname=current_schema()
          AND tablename IN ('erp_local_lot_position','erp_local_lot_action')
        """).splitlines()}
    required = {
        "erp_local_lot_position_warehouse_recent_idx",
        "erp_local_lot_position_expiry_page_idx",
        "erp_local_lot_position_receipt_root_idx",
        "erp_local_lot_action_source_recent_idx",
        "erp_local_lot_action_destination_recent_idx",
    }
    assert required <= names, ("Missing V49/V85/V86/V87 indexes", sorted(required-names))
    seed = psql("""
      SELECT p.tenant_id,p.id,p.warehouse_id,p.receipt_id,
             p.product_sku_id,p.bin_id,p.created_by
      FROM erp_local_lot_position p
      WHERE p.reason='V95 synthetic nonposting evidence position'
      ORDER BY p.id LIMIT 1
      """).split("|")
    assert len(seed) == 7, "V95 single-tenant synthetic receipt fixture absent"
    tenant, position, warehouse, receipt, sku, bin_id, actor = map(checked_uuid, seed)
    baseline = tuple(map(int, psql(f"""
      SELECT (SELECT count(*) FROM erp_local_lot_position WHERE tenant_id='{tenant}'),
             (SELECT count(*) FROM erp_local_lot_action WHERE tenant_id='{tenant}'),
             (SELECT count(*) FROM erp_local_stock_movement WHERE tenant_id='{tenant}')
      """).split("|")))
    def positions_query(after=""):
        # Mirrors V136 Spring: limit/order root position by index, then join
        # only the bounded source page to bin and quality metadata.
        return f"""
          WITH selected AS MATERIALIZED (
            SELECT p.id,p.tenant_id,p.receipt_id,p.warehouse_id,p.product_sku_id,
                   p.bin_id,p.placed_quantity,p.remaining_quantity,p.expiry_date,
                   p.created_at
            FROM erp_local_lot_position p
            WHERE p.tenant_id='{tenant}'::uuid
              AND p.warehouse_id='{warehouse}'::uuid
              {after}
            ORDER BY p.created_at DESC,p.id DESC LIMIT 51
          )
          SELECT p.id,p.receipt_id,p.warehouse_id,p.product_sku_id,p.bin_id,
                 z.bin_code,b.lot_code,p.placed_quantity,p.remaining_quantity,
                 p.expiry_date,p.created_at
          FROM selected p
          JOIN erp_local_stock_bin z
            ON z.tenant_id=p.tenant_id AND z.id=p.bin_id
          JOIN erp_receipt_quality_batch b
            ON b.tenant_id=p.tenant_id AND b.receipt_id=p.receipt_id
          ORDER BY p.created_at DESC,p.id DESC
        """
    history = f"""
      SELECT id,action_type,source_position_id,source_warehouse_id,source_bin_id,
             destination_warehouse_id,destination_bin_id,destination_position_id,
             operation_id,quantity,expected_remaining,expected_on_hand,
             observed_quantity,reason,actor_user_id,created_at
      FROM erp_local_lot_action
      WHERE tenant_id='{tenant}'::uuid
        AND (source_position_id='{position}'::uuid
             OR destination_position_id='{position}'::uuid)
    """
    def expiry_query(after=""):
        # Mirrors V136 Spring: eligible expiry index decides the page BEFORE
        # joining enriched lot code and bin code metadata.
        return f"""
          WITH selected AS MATERIALIZED (
            SELECT p.id,p.tenant_id,p.receipt_id,p.warehouse_id,p.bin_id,
                   p.product_sku_id,p.remaining_quantity,p.expiry_date
            FROM erp_local_lot_position p
            WHERE p.tenant_id='{tenant}'::uuid
              AND p.warehouse_id='{warehouse}'::uuid
              AND p.remaining_quantity>0 AND p.expiry_date<=CURRENT_DATE+30
              {after}
            ORDER BY p.expiry_date ASC,p.id ASC LIMIT 51
          )
          SELECT p.id,b.lot_code,z.bin_code,p.product_sku_id,p.remaining_quantity,
                 p.expiry_date,
                 CASE WHEN p.expiry_date<CURRENT_DATE THEN 'EXPIRED'
                      ELSE 'NEAR_EXPIRY' END AS urgency
          FROM selected p
          JOIN erp_receipt_quality_batch b
            ON b.tenant_id=p.tenant_id AND b.receipt_id=p.receipt_id
          JOIN erp_local_stock_bin z
            ON z.tenant_id=p.tenant_id AND z.id=p.bin_id
          ORDER BY p.expiry_date ASC,p.id ASC
        """
    allocation = f"""
      SELECT b.receipt_id,b.warehouse_id,b.product_sku_id,b.status,
             COALESCE(q.accepted_quantity,0)::integer AS accepted,
             COALESCE((
               SELECT SUM(p.placed_quantity)
               FROM erp_local_lot_position p
               WHERE p.tenant_id=b.tenant_id AND p.receipt_id=b.receipt_id
                 AND p.origin_position_id IS NULL
             ),0)::bigint AS placed
      FROM erp_receipt_quality_batch b
      LEFT JOIN erp_purchase_receipt_qc q
        ON q.tenant_id=b.tenant_id AND q.id=b.inspection_id
      WHERE b.tenant_id='{tenant}'::uuid AND b.receipt_id='{receipt}'::uuid
    """
    probes = {
        "position_start": positions_query(),
        "position_deep": positions_query("""
          AND (p.created_at,p.id) <
              (now()-interval '10500 seconds','ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
        """),
        "history_start":history+" ORDER BY created_at DESC,id DESC LIMIT 51",
        "history_deep":history+"""
          AND (created_at,id) <
              (now()-interval '180 seconds','ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
          ORDER BY created_at DESC,id DESC LIMIT 51""",
        "expiry_start":expiry_query(),
        "expiry_deep":expiry_query("""
          AND (p.expiry_date,p.id) >
              (CURRENT_DATE+10,'00000000-0000-0000-0000-000000000000'::uuid)
        """),
        "receipt_allocation":allocation,
    }
    # End of the full-suite fixture pipeline: transaction rollback is also a
    # safety assertion. No permanent rows, stock moves, or file artifacts.
    script = f"""
      BEGIN;
      -- Synthetic bulk insert / ANALYZE can take longer than a single
      -- bounded read; constrain individual EXPLAINs separately below.
      SET LOCAL statement_timeout='30000ms';
      SET LOCAL lock_timeout='2s';
      INSERT INTO erp_local_lot_position(
        id,tenant_id,request_key,receipt_id,warehouse_id,product_sku_id,
        bin_id,placed_quantity,remaining_quantity,expiry_date,reason,created_by,
        created_at)
      SELECT gen_random_uuid(),'{tenant}'::uuid,gen_random_uuid(),
             '{receipt}'::uuid,'{warehouse}'::uuid,'{sku}'::uuid,'{bin_id}'::uuid,
             1,1,
             CASE WHEN i%6=0 THEN NULL
                  WHEN i%3=0 THEN CURRENT_DATE+45
                  ELSE CURRENT_DATE+(i%25)-10 END,
             'V135 transaction-only query-plan fixture','{actor}'::uuid,
             now()-i*interval '1 second'
      FROM generate_series(1,{POSITION_COUNT}) AS i;
      INSERT INTO erp_local_lot_action(
        id,tenant_id,request_key,action_type,source_position_id,
        source_warehouse_id,source_bin_id,quantity,expected_remaining,
        observed_quantity,reason,actor_user_id,created_at)
      SELECT gen_random_uuid(),'{tenant}'::uuid,gen_random_uuid(),
             'COUNT_OBSERVATION',
             CASE WHEN i<=240 THEN '{position}'::uuid
                  ELSE spread.ids[1+((i-1)%100)] END,
             '{warehouse}'::uuid,'{bin_id}'::uuid,0,1,2,
             'V135 nonposting query-plan action','{actor}'::uuid,
             now()-i*interval '1 second'
      FROM generate_series(1,{ACTION_COUNT}) AS i
      CROSS JOIN (
        SELECT array_agg(id) AS ids FROM (
          SELECT id FROM erp_local_lot_position
          WHERE tenant_id='{tenant}'::uuid
            AND reason='V135 transaction-only query-plan fixture'
          ORDER BY id LIMIT 100
        ) sampled
      ) spread;
      ANALYZE erp_local_lot_position;
      ANALYZE erp_local_lot_action;
    """
    script += "SET LOCAL statement_timeout='6000ms';\n"
    for name, query in probes.items():
        script += (f"SELECT '{MARKER_PREFIX}{name}';\n"
                   f"EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) {query};\n"
                   "SELECT '__V135_END__';\n")
    script += "ROLLBACK;\n"
    output = psql(script, time_limit=150)
    plans = parse_explain_output(output)
    summaries = [assess(n, plans[n]) for n in PLAN_NAMES]
    # Restore disposable table statistics after the rolled-back fixtures.
    psql("ANALYZE erp_local_lot_position; ANALYZE erp_local_lot_action;")
    after = tuple(map(int, psql(f"""
      SELECT (SELECT count(*) FROM erp_local_lot_position WHERE tenant_id='{tenant}'),
             (SELECT count(*) FROM erp_local_lot_action WHERE tenant_id='{tenant}'),
             (SELECT count(*) FROM erp_local_stock_movement WHERE tenant_id='{tenant}')
      """).split("|")))
    assert baseline == after, ("Transaction-only fixtures were not rolled back",
                               baseline, after)
    for summary in summaries:
        print("V135 PLAN "+json.dumps(summary,sort_keys=True,separators=(",",":")))
    print(f"PASS V135 PostgreSQL18: {POSITION_COUNT} synthetic lot positions, "
          f"{ACTION_COUNT} nonposting actions, 7 real EXPLAIN ANALYZE BUFFERS; "
          "access-index and time regression guards; transaction rollback, "
          "unchanged signed stock; no real credentials or external writes")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif not sys.argv[1:]:
        benchmark()
    else:
        raise SystemExit("usage: check_lot_query_plans_v135.py [--self-test]")
