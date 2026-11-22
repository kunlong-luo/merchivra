package org.merchivra.erp.finance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V201 historical orders can be revised after two V34 reviews were written.
 * A per-event check is insufficient to protect later V63/COGS readiness.
 */
class RefundItemReviewServiceV201Test {
    @Test void crossEventQuantityConflictTakesPrecedenceOverClassification() {
        assertEquals("REFUND_QUANTITY_ACROSS_EVENTS_CONFLICT",
            RefundItemReviewService.refundSnapshotState(true,true));
        assertEquals("REFUND_QUANTITY_ACROSS_EVENTS_CONFLICT",
            RefundItemReviewService.refundSnapshotState(false,true));
        assertEquals("MANUAL_REFUND_ITEM_REVIEW",
            RefundItemReviewService.refundSnapshotState(true,false));
        assertEquals("REFUND_CLASSIFICATION_INCOMPLETE",
            RefundItemReviewService.refundSnapshotState(false,false));
    }

    @Test void sourceQueryReevaluatesEveryEventUsingCurrentLiveOrderQuantity() {
        String sql=RefundItemReviewService.QUANTITY_CONSERVATION_SQL;
        assertTrue(sql.contains("FROM finance_refund_item_review r"));
        assertTrue(sql.contains("r.tenant_id=? AND r.sales_order_id=?"));
        assertTrue(sql.contains("i.tenant_id=r.tenant_id"));
        assertTrue(sql.contains("i.sales_order_id=r.sales_order_id"));
        assertTrue(sql.contains("i.external_item_id=r.external_item_id"));
        assertTrue(sql.contains("i.is_current=true AND i.data_origin='LIVE'"));
        assertTrue(sql.contains("GROUP BY r.external_item_id,i.quantity"));
        assertTrue(sql.contains("SUM(r.reviewed_quantity)>i.quantity"));
        assertTrue(sql.contains("i.quantity IS NULL"));
        assertFalse(sql.contains("r.event_id=?"),
            "V201 must not accidentally scope the sum to one refund event");
        assertFalse(sql.contains("COALESCE(i.quantity,0)"),
            "Missing LIVE quantity is evidence unknown, not zero");
        assertFalse(sql.contains("INSERT "));
        assertFalse(sql.contains("UPDATE "));
    }
}
