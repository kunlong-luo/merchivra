-- V63 (product V65): local inbound plan, reported transit/receipts and variance evidence.
-- These rows are NOT Amazon-confirmed FBA inbound, available stock or finance postings.
CREATE TABLE fba_inbound_shipment (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 request_key UUID NOT NULL,
 request_sha256 CHAR(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 reference VARCHAR(100) NOT NULL,
 evidence_origin VARCHAR(24) NOT NULL CHECK(evidence_origin IN ('MOCK','MANUAL_UNVERIFIED')),
 state VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
   CHECK(state IN ('DRAFT','IN_TRANSIT','RECEIVING','REVIEWING','RECONCILED')),
 created_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 dispatched_at TIMESTAMPTZ,
 review_started_at TIMESTAMPTZ,
 reconciled_at TIMESTAMPTZ,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE (tenant_id,id),
 UNIQUE (tenant_id,seller_marketplace_id,request_key),
 UNIQUE (tenant_id,seller_marketplace_id,reference),
 FOREIGN KEY (tenant_id,seller_marketplace_id) REFERENCES seller_marketplace(tenant_id,id),
 FOREIGN KEY (tenant_id,created_by) REFERENCES membership(tenant_id,user_id),
 CHECK ((state='DRAFT')=(dispatched_at IS NULL)),
 CHECK (state NOT IN ('REVIEWING','RECONCILED') OR review_started_at IS NOT NULL),
 CHECK ((state='RECONCILED')=(reconciled_at IS NOT NULL))
);
CREATE INDEX fba_inbound_shipment_recent_v63
 ON fba_inbound_shipment(tenant_id,seller_marketplace_id,created_at DESC);

CREATE TABLE fba_inbound_line (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 shipment_id UUID NOT NULL,
 seller_sku VARCHAR(200) NOT NULL,
 planned_quantity INTEGER NOT NULL CHECK(planned_quantity BETWEEN 1 AND 1000000),
 UNIQUE (tenant_id,shipment_id,id),
 UNIQUE (tenant_id,shipment_id,seller_sku),
 FOREIGN KEY (tenant_id,shipment_id) REFERENCES fba_inbound_shipment(tenant_id,id)
);
CREATE INDEX fba_inbound_line_shipment_v63 ON fba_inbound_line(tenant_id,shipment_id);

CREATE TABLE fba_inbound_receipt_event (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 shipment_id UUID NOT NULL,
 line_id UUID NOT NULL,
 request_key UUID NOT NULL,
 quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
 evidence_reference VARCHAR(200) NOT NULL,
 evidence_origin VARCHAR(24) NOT NULL CHECK(evidence_origin IN ('MOCK','MANUAL_UNVERIFIED')),
 recorded_by UUID NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE (tenant_id,shipment_id,request_key),
 FOREIGN KEY (tenant_id,shipment_id,line_id)
   REFERENCES fba_inbound_line(tenant_id,shipment_id,id),
 FOREIGN KEY (tenant_id,recorded_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX fba_inbound_receipt_line_v63
 ON fba_inbound_receipt_event(tenant_id,shipment_id,line_id,recorded_at);

CREATE TABLE fba_inbound_variance_resolution (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 shipment_id UUID NOT NULL,
 line_id UUID NOT NULL,
 observed_quantity INTEGER NOT NULL CHECK(observed_quantity BETWEEN 0 AND 2000000),
 planned_quantity INTEGER NOT NULL CHECK(planned_quantity BETWEEN 1 AND 1000000),
 decision VARCHAR(24) NOT NULL CHECK(decision IN ('ACCEPT_VARIANCE')),
 note VARCHAR(500) NOT NULL CHECK(length(btrim(note)) BETWEEN 10 AND 500),
 reviewed_by UUID NOT NULL,
 reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE (tenant_id,shipment_id,line_id),
 FOREIGN KEY (tenant_id,shipment_id,line_id)
   REFERENCES fba_inbound_line(tenant_id,shipment_id,id),
 FOREIGN KEY (tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id),
 CHECK (observed_quantity<>planned_quantity)
);
