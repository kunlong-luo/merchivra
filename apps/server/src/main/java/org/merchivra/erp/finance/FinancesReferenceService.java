package org.merchivra.erp.finance;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Preserves Amazon Finances document identifiers only, never payment credentials
 * or a payout confirmation. Called within the same fenced page transaction.
 */
@Service
public class FinancesReferenceService {
 private final JdbcTemplate jdbc;
 public FinancesReferenceService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Reference(String kind,String value) {}

 @Transactional
 public void save(UUID tenant,UUID event,List<TypedFinancesSdkReader.Reference> refs) {
   if(refs==null||refs.size()>30)throw new IllegalArgumentException("Invalid Finances references");
   Integer source=jdbc.queryForObject("""
     SELECT count(*) FROM finance_source_event
     WHERE tenant_id=? AND id=? AND source_family='SPAPI_FINANCES' AND data_origin='LIVE'
     """,Integer.class,tenant,event);
   if(source==null||source!=1)throw new SecurityException("Trusted Finances source required");
   for(var value:refs) {
     if(value==null || !List.of("SETTLEMENT_ID","DISBURSEMENT_ID",
           "FINANCIAL_EVENT_GROUP_ID").contains(value.kind())
       || value.value()==null
       || !value.value().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))
       throw new IllegalArgumentException("Invalid document reference");
     jdbc.update("""
       INSERT INTO finance_transaction_reference
         (tenant_id,event_id,reference_kind,reference_value)
       VALUES (?,?,?,?) ON CONFLICT DO NOTHING
       """,tenant,event,value.kind(),value.value());
   }
 }
 public List<Reference> list(UUID tenant,UUID marketplace,UUID event) {
   Integer count=jdbc.queryForObject("""
     SELECT count(*) FROM finance_source_event
     WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
       AND source_family='SPAPI_FINANCES' AND data_origin='LIVE'
     """,Integer.class,tenant,marketplace,event);
   if(count==null||count!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
   return jdbc.query("""
     SELECT reference_kind,reference_value FROM finance_transaction_reference
     WHERE tenant_id=? AND event_id=?
     ORDER BY reference_kind,reference_value
     """,(r,n)->new Reference(r.getString(1),r.getString(2)),tenant,event);
 }
}
