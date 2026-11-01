-- V67 business milestone / Flyway V65. Extend the EXISTING V10 operations
-- queue. No parallel alert engine, platform write, or automatic messaging.
ALTER TABLE ops_finding DROP CONSTRAINT ops_finding_rule_code_check;
ALTER TABLE ops_finding ADD CONSTRAINT ops_finding_rule_code_check
 CHECK (rule_code IN ('UNMAPPED_LISTING','INVENTORY_UNKNOWN','LOW_STOCK',
  'REFUND_REVIEW','FBA_INBOUND_REVIEW','ADS_REPORT_REVIEW'));
ALTER TABLE ops_finding
 ADD COLUMN assigned_to UUID,
 ADD COLUMN claimed_at TIMESTAMPTZ,
 ADD COLUMN snoozed_until TIMESTAMPTZ,
 ADD CONSTRAINT ops_finding_assignee_fk
   FOREIGN KEY (tenant_id,assigned_to) REFERENCES membership(tenant_id,user_id),
 ADD CONSTRAINT ops_finding_claim_pair_chk
   CHECK ((assigned_to IS NULL)=(claimed_at IS NULL));
CREATE INDEX ops_finding_assignment_v65
 ON ops_finding(tenant_id,assigned_to,state,changed_at DESC);
CREATE INDEX ops_finding_snooze_v65
 ON ops_finding(tenant_id,snoozed_until)
 WHERE snoozed_until IS NOT NULL;

-- Immutable, tenant-scoped action history; no email/Push/Amazon output.
CREATE TABLE ops_finding_attention_audit (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 finding_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 action VARCHAR(20) NOT NULL CHECK(action IN ('CLAIMED','RELEASED','SNOOZED','UNSNOOZED')),
 old_assignee UUID,
 new_assignee UUID,
 old_snoozed_until TIMESTAMPTZ,
 new_snoozed_until TIMESTAMPTZ,
 note VARCHAR(300),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,finding_id) REFERENCES ops_finding(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,old_assignee) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,new_assignee) REFERENCES membership(tenant_id,user_id),
 CHECK (note IS NULL OR length(btrim(note)) BETWEEN 10 AND 300)
);
CREATE INDEX ops_attention_history_v65
 ON ops_finding_attention_audit(tenant_id,finding_id,created_at DESC,id DESC);
