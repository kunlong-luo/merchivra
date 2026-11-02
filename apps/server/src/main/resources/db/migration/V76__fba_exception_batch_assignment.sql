-- Product V79 / Flyway V76. Extend V77's existing atomic/idempotent batch ledger.
-- No separate assignment engine, operational queue, Amazon writes or new permission model.
ALTER TABLE fba_purchase_exception_batch
 RENAME COLUMN acknowledged_count TO affected_count;
ALTER TABLE fba_purchase_exception_batch
 ADD COLUMN action_type VARCHAR(16) NOT NULL DEFAULT 'ACKNOWLEDGE'
   CHECK(action_type IN ('ACKNOWLEDGE','ASSIGN')),
 ADD COLUMN assignee_id UUID,
 ADD CONSTRAINT fba_purchase_batch_assignment_owner_v76
   FOREIGN KEY(tenant_id,assignee_id) REFERENCES membership(tenant_id,user_id),
 ADD CONSTRAINT fba_purchase_batch_type_v76
   CHECK((action_type='ACKNOWLEDGE' AND assignee_id IS NULL)
      OR (action_type='ASSIGN' AND assignee_id IS NOT NULL));
-- Unique (tenant,marketplace,request_key) continues to prevent action-type replay collisions.
