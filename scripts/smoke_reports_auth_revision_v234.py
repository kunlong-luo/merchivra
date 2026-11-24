#!/usr/bin/env python3
"""V234: actual installed V116 authorization revisions in disposable PG18.
No real credentials, Amazon requests, HTTP authorization or accounting approval.
"""
import os
import subprocess
import time
from uuid import uuid4

for key, expected in {
    "MERCHIVRA_V234_DISPOSABLE_ONLY": "true",
    "GITHUB_ACTIONS": "true",
    "GITHUB_REPOSITORY": "kunlong-luo/merchivra",
    "GITHUB_REF": "refs/heads/main",
    "MERCHIVRA_SPAPI_ENABLED": "false",
    "MERCHIVRA_SPAPI_OAUTH_ENABLED": "false",
    "DB_HOST": "127.0.0.1", "DB_PORT": "5432",
    "DB_NAME": "merchivra", "DB_USER": "merchivra",
    "DB_PASSWORD": "ci_disposable_database_password",
    "PGPASSWORD": "ci_disposable_database_password",
    "MERCHIVRA_BOOTSTRAP_USERNAME": "v65-ci-owner",
    "MERCHIVRA_BOOTSTRAP_PASSWORD": "ci-disposable-v65-only-secret-0000",
}.items():
    if os.environ.get(key) != expected:
        raise SystemExit("V234 requires isolated CI: " + key)
if not os.environ.get("GITHUB_RUN_ID", "").isdigit():
    raise SystemExit("V234 requires GitHub run ID")

P = ["psql", "-X", "-qAt", "-h", "127.0.0.1", "-p", "5432",
     "-U", "merchivra", "-d", "merchivra", "-v", "ON_ERROR_STOP=1"]


def pg(statement, expected_error=None):
    p = subprocess.run(P + ["-c", statement], capture_output=True,
                       text=True, timeout=20)
    if expected_error:
        assert p.returncode and expected_error in p.stderr, p.stderr[-1500:]
        return
    assert p.returncode == 0, p.stderr[-1500:]
    return p.stdout.strip()


assert 180000 <= int(pg("SHOW server_version_num")) < 190000
assert pg("SELECT count(*) FROM flyway_schema_history WHERE version='116' AND success") == "1"
assert pg("""SELECT count(*) FROM pg_trigger
 WHERE NOT tgisinternal AND tgname IN ('spapi_grant_report_revision_v116',
 'spapi_role_report_revision_v116','spapi_report_revision_immutable_v116')""") == "3"


def fixture(label):
    tenant, user, account, market, grant, job, report = (str(uuid4()) for _ in range(7))
    seller = "V234-SYNTHETIC-" + label + "-" + account
    pg(f"""BEGIN;
 INSERT INTO tenant(id,name) VALUES('{tenant}','V234 DISPOSABLE {label}');
 INSERT INTO app_user(id,identity_ref) VALUES('{user}','local:v234:{user}');
 INSERT INTO membership(tenant_id,user_id) VALUES('{tenant}','{user}');
 INSERT INTO seller_account(id,tenant_id,seller_id,region,connection_status)
 VALUES('{account}','{tenant}','{seller}','NA','CONNECTED');
 INSERT INTO seller_marketplace(id,tenant_id,seller_account_id,marketplace_id,currency_code,timezone)
 VALUES('{market}','{tenant}','{account}','V234-{label}','USD','Etc/UTC');
 INSERT INTO spapi_seller_authorization(id,tenant_id,seller_account_id,seller_id,region,status,
 ciphertext,nonce,verified_at)
 VALUES('{grant}','{tenant}','{account}','{seller}','NA','VERIFIED',
 decode('01','hex'),decode('02','hex'),now());
 INSERT INTO spapi_finances_role_evidence(tenant_id,seller_account_id,role_code,evidence_kind,
 evidence_sha256,grant_verified_at,reviewed_by,reviewed_at,expires_at)
 SELECT tenant_id,seller_account_id,'Finance and Accounting','EXTERNAL_APP_ROLE_REVIEW',
 repeat('a',64),verified_at,'{user}',now(),now()+interval '1 day'
 FROM spapi_seller_authorization WHERE id='{grant}';
 INSERT INTO spapi_settlement_scan_job(id,tenant_id,seller_marketplace_id,seller_account_id,
 created_by,window_after,window_before,next_token,token_issued_at,row_offset)
 VALUES('{job}','{tenant}','{market}','{account}','{user}',now()-interval '2 days',
 now()-interval '1 day','synthetic-cursor',now(),1);
 INSERT INTO spapi_settlement_report(id,tenant_id,seller_marketplace_id,amazon_report_id,
 report_document_id,report_type,state,source_origin,document_sha256,settlement_reference,
 statement_currency,declared_net,line_count)
 VALUES('{report}','{tenant}','{market}','V234-REPORT-{label}','V234-DOC-{label}',
 'GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE_V2','IMPORTED','LIVE',
 repeat('b',64),'V234-SETTLEMENT-{label}','USD',NULL,1);
 COMMIT;""")
    return tenant, user, account, market, grant, job, report


a, b = fixture("A"), fixture("B")


def where(f):
    return f"tenant_id='{f[0]}' AND seller_account_id='{f[2]}'"


def versions(f):
    return tuple(map(int, pg(f"""SELECT g.report_auth_revision,re.report_auth_revision
 FROM spapi_seller_authorization g JOIN spapi_finances_role_evidence re
 ON re.tenant_id=g.tenant_id AND re.seller_account_id=g.seller_account_id
 WHERE g.tenant_id='{f[0]}' AND g.seller_account_id='{f[2]}'""").split("|")))


def grant_update(f, assignment):
    return f"UPDATE spapi_seller_authorization SET {assignment} WHERE {where(f)}"


def role_update(f, assignment):
    return f"UPDATE spapi_finances_role_evidence SET {assignment} WHERE {where(f)}"


a0, b0 = versions(a), versions(b)
assert min(*a0, *b0) > 0 and len(set(a0+b0)) == 4
# Old reports/jobs retain missing provenance; do not silently manufacture it.
assert pg(f"SELECT report_grant_revision IS NULL AND report_role_revision IS NULL FROM spapi_settlement_scan_job WHERE id='{a[5]}'") == "t"
assert pg(f"SELECT report_grant_revision IS NULL AND report_role_revision IS NULL FROM spapi_settlement_report WHERE id='{a[6]}'") == "t"
pg(f"UPDATE spapi_settlement_scan_job SET report_grant_revision={a0[0]} WHERE id='{a[5]}'",
   "settlement_scan_revision_pair_v116")
pg(f"UPDATE spapi_settlement_report SET report_grant_revision={a0[0]},report_role_revision={a0[1]} WHERE id='{a[6]}'",
   "Immutable report authorization revision")
pg(f"""UPDATE spapi_settlement_scan_job SET report_grant_revision={a0[0]},
 report_role_revision={a0[1]} WHERE id='{a[5]}'""")

# Each pg() invocation opens a new connection; two separate commits restore
# the original encrypted bytes/status, but not the original revisions.
pg(grant_update(a, "status='REVOKED',ciphertext=NULL,nonce=NULL"))
revoked = versions(a)
pg(grant_update(a, "status='VERIFIED',ciphertext=decode('01','hex'),nonce=decode('02','hex')"))
a1 = versions(a)
assert a1[0] > revoked[0] > a0[0] and a1[1] == a0[1]
assert pg(f"SELECT status||':'||encode(ciphertext,'hex') FROM spapi_seller_authorization WHERE {where(a)}") == "VERIFIED:01"
pg(role_update(a, "revoked_at=now()"))
role_revoked = versions(a)
pg(role_update(a, "revoked_at=NULL"))
a2 = versions(a)
assert a2[1] > role_revoked[1] > a1[1] and a2[0] == a1[0]
assert versions(b) == b0
assert pg(f"""SELECT j.report_grant_revision=g.report_auth_revision
 AND j.report_role_revision=re.report_auth_revision
 FROM spapi_settlement_scan_job j JOIN spapi_seller_authorization g
 ON g.tenant_id=j.tenant_id AND g.seller_account_id=j.seller_account_id
 JOIN spapi_finances_role_evidence re ON re.tenant_id=g.tenant_id
 AND re.seller_account_id=g.seller_account_id WHERE j.id='{a[5]}'""") == "f"

# Rollback does not change the stored revision, even if a sequence value is consumed.
pg("BEGIN;" + grant_update(a, "status=status") + ";" +
   role_update(a, "expires_at=expires_at") + ";ROLLBACK;")
assert versions(a) == a2
pg(grant_update(a, "report_auth_revision=1"))
assert versions(a)[0] > a2[0]

# Actual grant/role row share-lock prevents concurrent mutation. No sleep-only
# synchronization: the holder first acquires row locks then an advisory signal.
holder = subprocess.Popen(P, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                          stderr=subprocess.PIPE, text=True)
try:
    holder.stdin.write(f"""BEGIN;
SELECT g.report_auth_revision FROM spapi_seller_authorization g
 JOIN spapi_finances_role_evidence re ON re.tenant_id=g.tenant_id
 AND re.seller_account_id=g.seller_account_id
 WHERE g.tenant_id='{a[0]}' AND g.seller_account_id='{a[2]}' FOR SHARE OF g,re;
SELECT pg_advisory_lock(234,1);
""")
    holder.stdin.flush()
    for _ in range(60):
        if pg("""SELECT count(*) FROM pg_locks WHERE locktype='advisory'
 AND granted AND classid=234 AND objid=1 AND objsubid=2""") == "1":
            break
        time.sleep(0.1)
    else:
        raise AssertionError("V234 lock holder did not acquire row locks")
    for statement in (grant_update(a, "status=status"), role_update(a, "expires_at=expires_at")):
        pg("SET lock_timeout='500ms';" + statement, "lock timeout")
    pg(grant_update(b, "status=status"))
    assert versions(b)[0] > b0[0]  # other tenant proceeds while A locked
finally:
    if holder.poll() is None:
        holder.stdin.write("ROLLBACK;\nSELECT pg_advisory_unlock(234,1);\n\\q\n")
        holder.stdin.flush()
        holder.communicate(timeout=8)
assert holder.returncode == 0
before = versions(a)
pg(role_update(a, "expires_at=expires_at"))
assert versions(a)[1] > before[1]

# New report captures the current pair; subsequent edits are rejected.
pair = versions(a)
new_report = str(uuid4())
pg(f"""INSERT INTO spapi_settlement_report(id,tenant_id,seller_marketplace_id,
 amazon_report_id,report_document_id,report_type,state,source_origin,document_sha256,
 settlement_reference,statement_currency,line_count,report_grant_revision,report_role_revision)
 VALUES('{new_report}','{a[0]}','{a[3]}','V234-NEW-REPORT','V234-NEW-DOC',
 'GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE_V2','IMPORTED','LIVE',repeat('c',64),
 'V234-NEW-SETTLEMENT','USD',1,{pair[0]},{pair[1]})""")
pg(f"UPDATE spapi_settlement_report SET report_role_revision=NULL WHERE id='{new_report}'",
   "Immutable report authorization revision")
assert pg(f"SELECT report_grant_revision||':'||report_role_revision FROM spapi_settlement_report WHERE id='{new_report}'") == f"{pair[0]}:{pair[1]}"
# Delete/reinsert role with identical facts, then grant with identical facts:
# no revision reuse. Synthetic job/report snapshots remain unchanged.
pg(f"""CREATE TEMP TABLE v234_saved_role AS SELECT * FROM spapi_finances_role_evidence WHERE {where(a)};
 CREATE TEMP TABLE v234_saved_grant AS SELECT * FROM spapi_seller_authorization WHERE {where(a)};
 DELETE FROM spapi_finances_role_evidence WHERE {where(a)};
 DELETE FROM spapi_seller_authorization WHERE {where(a)};
 INSERT INTO spapi_seller_authorization SELECT * FROM v234_saved_grant;
 INSERT INTO spapi_finances_role_evidence SELECT * FROM v234_saved_role;""")
rebuilt = versions(a)
assert rebuilt[0] > pair[0] and rebuilt[1] > pair[1]
assert pg(f"SELECT report_grant_revision||':'||report_role_revision FROM spapi_settlement_report WHERE id='{new_report}'") == f"{pair[0]}:{pair[1]}"
print("PASS V234 actual PG18 V116 grant/role revisions: committed ABA, rollback, reinsert, scoped row locks, stale job pair, immutable report pair; no external authentication")
