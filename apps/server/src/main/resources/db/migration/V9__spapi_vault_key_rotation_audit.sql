-- V9 rewrap audit is metadata-only; no keys, tokens or raw ciphertext.
-- Keep V7/V8 immutable. The key_version column was created in V7.
ALTER TABLE spapi_oauth_audit
  DROP CONSTRAINT spapi_oauth_audit_event_type_check;
ALTER TABLE spapi_oauth_audit
  ADD CONSTRAINT spapi_oauth_audit_event_type_check
  CHECK (event_type IN (
      'STARTED','HANDOFF','CODE_EXCHANGED','EXCHANGE_FAILED','REVOKED',
      'VERIFICATION_STARTED','VERIFICATION_FAILED','VERIFIED','KEY_REWRAPPED'
  ));
