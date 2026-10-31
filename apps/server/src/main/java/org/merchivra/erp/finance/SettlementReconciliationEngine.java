package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deliberately NOT a payout confirmation. Source transactions can represent
 * net movements rather than line-level sales, and statement input is manual.
 */
public final class SettlementReconciliationEngine {
    private SettlementReconciliationEngine() {}
    public record Event(String id,String sourceEventKey,String sourceType,
                        BigDecimal amount,String currency,Instant postedAt) {}
    public record Line(String eventId,String sourceEventKey,String sourceType,
                       String signedAmount,String currency,String postedAt,
                       boolean withinPeriod) {}
    public record Preview(String status,String statementCurrency,String statementNet,
        String linkedSourceNet,String signedDifference,int linkedCount,
        int outsidePeriodCount,int unassignedSourceCount,
        boolean bankVerified,List<Line> linkedEvents) {}

    public static Preview compare(BigDecimal statementNet,String statementCurrency,
        Instant periodStart,Instant periodEnd,List<Event> events,int unassigned) {
        Objects.requireNonNull(statementNet);
        Objects.requireNonNull(statementCurrency);
        Objects.requireNonNull(periodStart);
        Objects.requireNonNull(periodEnd);
        Objects.requireNonNull(events);
        if(!periodStart.isBefore(periodEnd)||unassigned<0||events.size()>1000)
            throw new IllegalArgumentException("Invalid settlement comparison scope");
        BigDecimal sourceSum=BigDecimal.ZERO;
        int outside=0;
        boolean currencyMismatch=false;
        var lines=new ArrayList<Line>();
        var ids=new java.util.HashSet<String>();
        for(Event event:events) {
            if(event==null||event.id()==null||event.amount()==null
               ||event.currency()==null||event.postedAt()==null
               ||!ids.add(event.id()))
                throw new IllegalArgumentException("Invalid or duplicate source event");
            boolean inPeriod=!event.postedAt().isBefore(periodStart)
                &&event.postedAt().isBefore(periodEnd);
            if(!inPeriod)outside++;
            if(!statementCurrency.equals(event.currency()))currencyMismatch=true;
            else sourceSum=sourceSum.add(event.amount());
            lines.add(new Line(event.id(),event.sourceEventKey(),event.sourceType(),
                event.amount().toPlainString(),event.currency(),
                event.postedAt().toString(),inPeriod));
        }
        String status=events.isEmpty()?"NO_LINKED_EVENTS":
            currencyMismatch?"CURRENCY_MISMATCH":
            outside>0?"OUTSIDE_STATEMENT_PERIOD":
            sourceSum.compareTo(statementNet)!=0?"SIGNED_TOTAL_DIFFERS":
            "SIGNED_TOTAL_MATCHES_UNVERIFIED";
        return new Preview(status,statementCurrency,statementNet.toPlainString(),
            currencyMismatch?null:sourceSum.toPlainString(),
            currencyMismatch?null:statementNet.subtract(sourceSum).toPlainString(),
            events.size(),outside,unassigned,false,List.copyOf(lines));
    }
}
