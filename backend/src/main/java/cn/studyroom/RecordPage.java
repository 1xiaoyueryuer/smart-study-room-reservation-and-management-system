package cn.studyroom;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** The five current record domains share strict page/date parsing and SQL ID selection. */
final class RecordPage {
    enum Domain { RESERVATION,VIOLATION,RESTRICTION,COMPLAINT,APPEAL }
    final Map<String,String> filters;final int page,size;final boolean all;
    private final LocalDate from,to;
    private RecordPage(Map<String,String> values,Domain domain,boolean admin,boolean all){
        var allowed=new HashSet<>(Set.of("page","page_size","date_from","date_to","status"));if(admin)allowed.add("user_id");if(domain==Domain.RESERVATION)allowed.addAll(Set.of("room_id","seat_no","record_type"));if(domain==Domain.COMPLAINT)allowed.addAll(Set.of("room_id","type"));if(domain==Domain.VIOLATION)allowed.add("type");Api.require(allowed.containsAll(values.keySet()),400,"查询包含不允许的筛选或身份参数");filters=new HashMap<>(values);this.all=all;page=positive(values.getOrDefault("page","1"),"page");size=positive(values.getOrDefault("page_size","20"),"page_size");Api.require(size<=50,400,"每页最多50条");from=date(values.get("date_from"));to=date(values.get("date_to"));Api.require(from==null||to==null||!from.isAfter(to),400,"开始日期不得晚于结束日期");for(String key:List.of("user_id","room_id"))if(values.containsKey(key))positiveLong(values.get(key),key);if(values.containsKey("seat_no")){String seat=values.get("seat_no").trim();Api.require(!seat.isEmpty(),400,"座位编号不能为空");filters.put("seat_no",seat);}
        Set<String> statuses=switch(domain){case RESERVATION->Set.of("pending","checked_in","completed","cancelled","unused_ended","violation","temp_leave");case VIOLATION->Set.of("active","revoked");case RESTRICTION->Set.of("active","expired","released");case COMPLAINT->Set.of("submitted","processing","awaiting_confirmation","closed","withdrawn");case APPEAL->Set.of("submitted","approved","rejected");};choice("status",statuses);if(domain==Domain.VIOLATION)choice("type",Set.of("no_show","abandon","occupation_refusal"));if(domain==Domain.COMPLAINT)choice("type",Set.of("occupation","service"));if(domain==Domain.RESERVATION)choice("record_type",Set.of("reservation","usage"));
    }
    static RecordPage parse(Map<String,String> values,Domain domain,boolean admin){return new RecordPage(values,domain,admin,false);}
    static RecordPage legacy(Map<String,String> values,Domain domain,boolean admin){return new RecordPage(values,domain,admin,true);}
    Long user(){return filters.containsKey("user_id")?positiveLong(filters.get("user_id"),"user_id"):null;}
    private void choice(String name,Set<String> allowed){Api.require(!filters.containsKey(name)||allowed.contains(filters.get(name)),400,"筛选"+name+"无效");}
    private static int positive(String value,String name){long parsed=positiveLong(value,name);Api.require(parsed<=Integer.MAX_VALUE,400,name+"超出范围");return (int)parsed;}
    private static long positiveLong(String value,String name){try{Api.require(value!=null&&value.matches("[0-9]+"),400,name+"须为正整数");long parsed=Long.parseLong(value);Api.require(parsed>0,400,name+"须为正整数");return parsed;}catch(NumberFormatException e){throw Api.fail(400,name+"超出范围");}}
    private static LocalDate date(String value){if(value==null)return null;try{Api.require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),400,"日期须为ISO自然日");LocalDate date=LocalDate.parse(value);Api.require(date.getYear()>=1,400,"日期年份须为0001至9999");return date;}catch(java.time.format.DateTimeParseException e){throw Api.fail(400,"日期无效");}}
    record Selection(List<Long> ids,long total){}
    Selection select(Store db,Domain domain,Long owner,LocalDateTime snapshot){
        String table=switch(domain){case RESERVATION->"reservations";case VIOLATION->"violations";case RESTRICTION->"restrictions";case COMPLAINT->"complaints";case APPEAL->"appeals";};String dateColumn=switch(domain){case RESERVATION->"res_date";case VIOLATION->"occurred_at";case RESTRICTION->"created_at";default->"submitted_at";};String joins=domain==Domain.RESERVATION?" JOIN seats s ON s.id=x.seat_id":"";var where=new StringBuilder(" WHERE 1=1");var args=new ArrayList<Object>();if(owner!=null){where.append(" AND x.user_id=?");args.add(owner);}if(from!=null){where.append(" AND x."+dateColumn+">=CAST(? AS DATE)");args.add(from.toString());}if(to!=null){where.append(" AND x."+dateColumn+(domain==Domain.RESERVATION?"<=CAST(? AS DATE)":"<(CAST(? AS DATE)+INTERVAL '1 day')"));args.add(to.toString());}
        for(String key:List.of("status","type","room_id","seat_no")){if(!filters.containsKey(key))continue;if(key.equals("status")&&domain==Domain.RESTRICTION){where.append(" AND CASE WHEN x.released_at IS NOT NULL THEN 'released' WHEN x.ends_at<=? THEN 'expired' ELSE 'active' END=?");args.add(Timestamp.valueOf(snapshot));args.add(filters.get(key));}else{where.append(" AND "+(domain==Domain.RESERVATION&&(key.equals("room_id")||key.equals("seat_no"))?"s.":"x.")+key+"=?");args.add(key.equals("room_id")?positiveLong(filters.get(key),key):filters.get(key));}}
        if("usage".equals(filters.get("record_type")))where.append(" AND EXISTS(SELECT 1 FROM usage_records u WHERE u.reservation_id=x.id)");String fromSql=" FROM "+table+" x"+joins+where;long total=db.count("SELECT count(*)"+fromSql,args.toArray());String order=" ORDER BY x."+dateColumn+" DESC,"+(domain==Domain.RESERVATION?"x.start_time DESC,":"")+"x.id DESC";if(!all){args.add(size);args.add((long)(page-1)*size);}var ids=db.list("SELECT x.id"+fromSql+order+(all?"":" LIMIT ? OFFSET ?"),args.toArray()).stream().map(row->Store.id(row,"id")).toList();return new Selection(ids,total);
    }
    Map<String,Object> result(List<Map<String,Object>> items,long total,LocalDateTime now){return Map.of("items",items,"total",total,"page",page,"page_size",size,"server_now",AttachmentService.offset(now));}
    static String marks(List<Long> ids){return String.join(",",Collections.nCopies(ids.size(),"?"));}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> items(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}
}
