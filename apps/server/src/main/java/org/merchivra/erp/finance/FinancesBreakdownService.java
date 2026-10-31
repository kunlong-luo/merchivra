package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Evidence-only hierarchy: parent and nested amounts must not be summed. */
@Service
public class FinancesBreakdownService {
 private final JdbcTemplate jdbc;
 public FinancesBreakdownService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Node(String path,String parent,String sourceType,String amount,
                    String currency,boolean leaf) {}
 private static Node map(java.sql.ResultSet r,int i)throws java.sql.SQLException{
  var amount=r.getBigDecimal("original_amount"),currency=r.getString("currency_code");
  return new Node(r.getString("source_path"),r.getString("parent_path"),
    r.getString("source_type"),amount==null?null:amount.toPlainString(),
    currency==null?null:currency.trim(),r.getBoolean("is_leaf"));
 }
 private void ensure(UUID tenant,UUID marketplace,UUID event) {
  Integer count=jdbc.queryForObject("""
    SELECT count(*) FROM finance_source_event
    WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
    """,Integer.class,tenant,marketplace,event);
  if(count==null||count!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
 }
 public List<Node> list(UUID tenant,UUID marketplace,UUID event) {
  ensure(tenant,marketplace,event);
  return jdbc.query("""
    SELECT source_path,parent_path,source_type,original_amount,
      currency_code,is_leaf FROM finance_transaction_breakdown
    WHERE tenant_id=? AND event_id=?
    ORDER BY source_path
    """,FinancesBreakdownService::map,tenant,event);
 }
 /** Only a trusted worker calls this after recording a scoped LIVE source event. */
 @Transactional
 public void save(UUID tenant,UUID event,List<TypedFinancesSdkReader.BreakdownNode> items) {
  if(items==null||items.size()>2000)throw new IllegalArgumentException("Invalid breakdown page");
  Integer live=jdbc.queryForObject("""
    SELECT count(*) FROM finance_source_event
    WHERE tenant_id=? AND id=? AND source_family='SPAPI_FINANCES'
      AND data_origin='LIVE'
    """,Integer.class,tenant,event);
  if(live==null||live!=1)throw new SecurityException("Approved LIVE financial source required");
  if(items.isEmpty())return; // Missing details is not proof of an empty source.
  for(var x:items){
   BigDecimal value=x.amount()==null?null:new BigDecimal(x.amount());
   int n=jdbc.update("""
     INSERT INTO finance_transaction_breakdown
       (tenant_id,event_id,source_path,parent_path,source_type,
        original_amount,currency_code,is_leaf)
     VALUES (?,?,?,?,?,?,?,?)
     ON CONFLICT (tenant_id,event_id,source_path) DO NOTHING
     """,tenant,event,x.path(),x.parent(),x.type(),value,x.currency(),x.leaf());
   if(n==0) {
    List<Node> older=jdbc.query("""
      SELECT source_path,parent_path,source_type,original_amount,
        currency_code,is_leaf FROM finance_transaction_breakdown
      WHERE tenant_id=? AND event_id=? AND source_path=?
      """,FinancesBreakdownService::map,tenant,event,x.path());
    if(older.size()!=1)throw new IllegalStateException("Financial detail lost");
    Node previous=older.getFirst();
    if(!Objects.equals(previous.parent(),x.parent())
      || !previous.sourceType().equals(x.type())
      || previous.leaf()!=x.leaf()
      || !Objects.equals(previous.currency(),x.currency())
      || (previous.amount()==null)!=(x.amount()==null)
      || previous.amount()!=null
         && new BigDecimal(previous.amount()).compareTo(value)!=0)
      throw new ResponseStatusException(HttpStatus.CONFLICT,
        "Previously ingested financial breakdown differs");
   }
  }
  Integer count=jdbc.queryForObject("""
    SELECT count(*) FROM finance_transaction_breakdown
    WHERE tenant_id=? AND event_id=?
    """,Integer.class,tenant,event);
  if(count==null||count!=items.size())
   throw new ResponseStatusException(HttpStatus.CONFLICT,
     "Financial breakdown hierarchy changed between replays");
 }
}
