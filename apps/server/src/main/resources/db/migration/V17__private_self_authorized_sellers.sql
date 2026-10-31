-- V17: opt-in private Seller applications are self-authorized in Seller Central.
-- Never store the raw refresh token, import payload, or a false human actor.
ALTER TABLE spapi_seller_authorization
  ADD COLUMN authorization_method VARCHAR(24) NOT NULL DEFAULT 'PUBLIC_OAUTH'
    CHECK (authorization_method IN ('PUBLIC_OAUTH','PRIVATE_SELF'));

ALTER TABLE spapi_oauth_audit DROP CONSTRAINT spapi_oauth_audit_event_type_check;
ALTER TABLE spapi_oauth_audit ADD CONSTRAINT spapi_oauth_audit_event_type_check
 CHECK (event_type IN ('STARTED','HANDOFF','CODE_EXCHANGED','EXCHANGE_FAILED',
   'REVOKED','VERIFICATION_STARTED','VERIFICATION_FAILED','VERIFIED',
   'KEY_REWRAPPED','PRIVATE_IMPORTED','PRIVATE_REVOKED'));
