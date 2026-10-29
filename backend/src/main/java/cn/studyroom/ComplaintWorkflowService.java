package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** S16本域转换与期限收敛唯一写源。调用后错误由Controller映射，保已提交到期事实。 */
@Service
public class ComplaintWorkflowService {
    public record ActionResult(int status,String message,Map<String,Object> data,Object details) {}
    private final Store db;private final AuthService auth;private final AttachmentService attachments;private final ComplaintService complaints;private final ComplaintManagementService management;private final AuditService audit;private final ObjectMapper json;private final Clock clock;
    public ComplaintWorkflowService(Store db,AuthService auth,AttachmentService attachments,ComplaintService complaints,ComplaintManagementService management,AuditService audit,ObjectMapper json,Clock clock) {this.db=db;this.auth=auth;this.attachments=attachments;this.complaints=complaints;this.management=management;this.audit=audit;this.json=json;this.clock=clock;}
    @Transactional
    public List<Map<String,Object>> mine(long actor) {db.lockBookings();attachments.requireOrdinary(actor);var now=now();reconcile("c.user_id=?",List.of(actor),now);return complaints.mineAt(actor,now);}
    @Transactional
    public Map<String,Object> detail(long actor,long id) {db.lockBookings();owned(actor,id);var now=now();reconcile("c.id=?",List.of(id),now);return complaints.detailAt(actor,id,now);}
    @Transactional
    public List<Map<String,Object>> managementList(long actor,String status,String type,Long room) {
        db.lockBookings();requireAdmin(actor);ComplaintManagementService.validateFilters(status,type,room);var where=new StringBuilder("1=1");var args=new ArrayList<Object>();if(type!=null){where.append(" AND c.type=?");args.add(type);}if(room!=null){where.append(" AND c.room_id=?");args.add(room);}var now=now();reconcile(where.toString(),args,now);return management.listAt(actor,status,type,room,now);
    }
    @Transactional
    public Map<String,Object> managementDetail(long actor,long id) {db.lockBookings();requireAdmin(actor);required(id);var now=now();reconcile("c.id=?",List.of(id),now);return management.detailAt(actor,id,now);}
    @Transactional
    public Map<String,Object> feedback(long actor,long id,Requests.ComplaintFeedback request) {
        db.lockBookings();requireAdmin(actor);Api.require(request!=null,400,"反馈参数必填");String operation=AttachmentService.uuid(request.operationId(),"operation_id");var saved=operation(operation);String result,basis;
        try {result=text(request.result(),2000,"处理结果");basis=text(request.basis(),2000,"反馈依据");}catch(Api.Failure failure){if(saved!=null)throw Api.fail(409,"操作标识已用于其他内容，请按原请求重试");throw failure;}
        var intent=new TreeMap<String,Object>();intent.put("version",request.version());intent.put("result",result);intent.put("basis",basis);String hash=hash(actor,id,"feedback",intent);var now=now();var replay=replay(saved,actor,id,"feedback",operation,hash,true,now);if(replay!=null)return replay;
        var before=required(id);version(before,request.version());Api.require("processing".equals(before.get("status")),409,"仅处理中投诉可发送正式反馈，请刷新当前阶段");var deadline=now.plusDays(7);
        long event=db.insert("INSERT INTO complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,feedback_result,feedback_basis,feedback_deadline,public_note) VALUES(?,?,'feedback','admin_self_report',?,CAST(? AS UUID),?,?,?,?,?)",id,actor,Timestamp.valueOf(now),operation,hash,result,basis,Timestamp.valueOf(deadline),"管理员已发送处理结果及依据，请在确认期内确认或提出异议");
        db.update("UPDATE complaints SET status='awaiting_confirmation',version=version+1 WHERE id=?",id);notifyOwner(before,"complaint_feedback","投诉处理结果已反馈","请查看处理结果及依据，确认截止为"+deadline.atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime()+"；截止前可确认或提出异议。",now);
        audit.record(actor,"complaint.feedback","complaint",Long.toString(id),"管理员正式反馈并开始固定七天确认期",snapshot(before),Map.of("status","awaiting_confirmation","version",request.version()+1,"event_id",event,"feedback_deadline",deadline.toString()));return receipt(id,event,operation,false,true,actor,now);
    }
    @Transactional
    public ActionResult action(long actor,long id,String action,Requests.ComplaintResponse request) {
        db.lockBookings();var before=owned(actor,id);Api.require(Set.of("withdraw","confirm","object").contains(action),400,"投诉动作无效");Api.require(request!=null,400,"投诉操作参数必填");String operation=AttachmentService.uuid(request.operationId(),"operation_id");String type=switch(action){case "withdraw"->"withdrawn";case "confirm"->"confirmed";default->"objected";};var saved=operation(operation);String description=null;
        try {if(action.equals("object"))description=text(request.description(),2000,"异议说明");else Api.require(request.description()==null,400,"本操作不接受异议说明");}catch(Api.Failure failure){if(saved!=null)throw Api.fail(409,"操作标识已用于其他内容，请按原请求重试");throw failure;}
        var intent=new TreeMap<String,Object>();intent.put("version",request.version());intent.put("description",description);String hash=hash(actor,id,type,intent);var now=now();var replay=replay(saved,actor,id,type,operation,hash,false,now);if(replay!=null)return new ActionResult(200,"ok",replay,null);
        if(!action.equals("withdraw")&&"awaiting_confirmation".equals(before.get("status"))) {
            var feedback=latest(id);if(!now.isBefore(LocalDateTime.parse(Store.text(feedback,"feedback_deadline")))) {autoClose(before,feedback,now);return late(actor,id,now);}
        }
        version(before,request.version());Long related=null;String status;
        if(action.equals("withdraw")) {Api.require("submitted".equals(before.get("status")),409,"仅待受理投诉可撤回");status="withdrawn";}
        else {Api.require("awaiting_confirmation".equals(before.get("status")),409,"当前投诉不处于待确认阶段，请刷新记录");var feedback=latest(id);related=Store.id(feedback,"id");status=action.equals("confirm")?"closed":"processing";}
        long event=db.insert("INSERT INTO complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,related_feedback_id,response_note,public_note) VALUES(?,?,?,'user_submit',?,CAST(? AS UUID),?,?,?,?)",id,actor,type,Timestamp.valueOf(now),operation,hash,related,description,action.equals("withdraw")?"投诉人撤回待受理投诉":action.equals("confirm")?"投诉人已确认本次反馈并结案":"投诉人提出异议，继续核查");
        db.update("UPDATE complaints SET status=?,version=version+1 WHERE id=?",status,id);audit.record(actor,"complaint."+action,"complaint",Long.toString(id),action.equals("withdraw")?"本人撤回未受理投诉":action.equals("confirm")?"本人在确认期内确认反馈":"本人在确认期内提出异议继续核查",snapshot(before),Map.of("status",status,"version",request.version()+1,"event_id",event));return new ActionResult(200,"ok",receipt(id,event,operation,false,false,actor,now),null);
    }
    @Transactional
    public int settleExpired() {db.lockBookings();return reconcile("1=1",List.of(),now());}
    private int reconcile(String where,List<?> args,LocalDateTime now) {
        var rows=db.list("SELECT c.*,f.id AS feedback_id,f.feedback_deadline,r.id AS response_id FROM complaints c LEFT JOIN LATERAL(SELECT id,feedback_deadline FROM complaint_events WHERE complaint_id=c.id AND event_type='feedback' ORDER BY id DESC LIMIT 1) f ON true LEFT JOIN complaint_events r ON r.related_feedback_id=f.id WHERE c.status='awaiting_confirmation' AND "+where,args.toArray());int count=0;
        for(var row:rows) {if(row.get("feedback_id")==null||row.get("feedback_deadline")==null||row.get("response_id")!=null)throw new IllegalStateException("投诉待确认阶段与反馈事实矛盾，请核查数据");if(!now.isBefore(LocalDateTime.parse(Store.text(row,"feedback_deadline")))) {autoClose(row,Map.of("id",row.get("feedback_id"),"feedback_deadline",row.get("feedback_deadline")),now);count++;}}return count;
    }
    private Map<String,Object> latest(long id) {var rows=db.list("SELECT f.*,r.id AS response_id FROM complaint_events f LEFT JOIN complaint_events r ON r.related_feedback_id=f.id WHERE f.complaint_id=? AND f.event_type='feedback' ORDER BY f.id DESC LIMIT 1",id);if(rows.isEmpty()||rows.get(0).get("response_id")!=null)throw new IllegalStateException("投诉当前反馈缺失或已响应，请核查数据");return rows.get(0);}
    private void autoClose(Map<String,Object> before,Map<String,Object> feedback,LocalDateTime now) {
        long id=Store.id(before,"id"),feedbackId=Store.id(feedback,"id");var current=required(id);if(!"awaiting_confirmation".equals(current.get("status")))return;var latest=latest(id);if(Store.id(latest,"id")!=feedbackId)throw new IllegalStateException("当前反馈周期已变化，拒绝旧周期结案");var deadline=LocalDateTime.parse(Store.text(feedback,"feedback_deadline"));if(now.isBefore(deadline))return;
        long event=db.insert("INSERT INTO complaint_events(complaint_id,actor_id,event_type,source,created_at,occurred_at,related_feedback_id,public_note) VALUES(?,NULL,'auto_closed','system',?,?,?,?)",id,Timestamp.valueOf(now),Timestamp.valueOf(deadline),feedbackId,"本次反馈确认期届满且无确认或异议，系统自动结案");db.update("UPDATE complaints SET status='closed',version=version+1 WHERE id=?",id);
        notifyOwner(current,"complaint_auto_closed","投诉确认期届满已自动结案","本次反馈七天确认期届满且无确认或异议，已自动结案；不表示投诉人已确认处理结果。",now);audit.record(null,"complaint.auto_close","complaint",Long.toString(id),"本次反馈七天期限届满且无确认或异议，自动结案",snapshot(current),Map.of("status","closed","version",Store.id(current,"version")+1,"event_id",event,"feedback_event_id",feedbackId,"deadline",deadline.toString()));
    }
    private ActionResult late(long actor,long id,LocalDateTime now) {var detail=complaints.detailAt(actor,id,now);var safe=new LinkedHashMap<String,Object>();for(String key:List.of("status","version","current_feedback","closure_type","closed_at"))safe.put(key,detail.get(key));safe.put("complaint_id",id);return new ActionResult(409,"本次反馈确认期已届满并自动结案，未执行确认或异议，请刷新记录",Map.of(),safe);}
    private Map<String,Object> operation(String key) {var rows=db.list("SELECT id,actor_id,complaint_id,event_type,request_hash FROM complaint_events WHERE operation_id=CAST(? AS UUID)",key);return rows.isEmpty()?null:rows.get(0);}
    private Map<String,Object> replay(Map<String,Object> saved,long actor,long id,String type,String operation,String hash,boolean admin,LocalDateTime now) {if(saved==null)return null;Api.require(saved.get("actor_id")!=null&&Store.id(saved,"actor_id")==actor&&Store.id(saved,"complaint_id")==id&&type.equals(saved.get("event_type"))&&hash.equals(saved.get("request_hash")),409,"操作标识已用于其他内容，请按原请求重试");return receipt(id,Store.id(saved,"id"),operation,true,admin,actor,now);}
    private Map<String,Object> receipt(long id,long event,String operation,boolean replayed,boolean admin,long actor,LocalDateTime now) {return Map.of("operation_id",operation,"event_id",event,"replayed",replayed,"complaint",admin?management.detailAt(actor,id,now):complaints.detailAt(actor,id,now));}
    private Map<String,Object> owned(long actor,long id) {attachments.requireOrdinary(actor);var row=required(id);Api.require(Store.id(row,"user_id")==actor,403,"无权操作他人的投诉");return row;}
    private Map<String,Object> required(long id) {return db.one("SELECT * FROM complaints WHERE id=?",id);}
    private void requireAdmin(long actor) {var user=auth.profile(actor);Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"需要启用的管理员权限");}
    private void version(Map<String,Object> before,Long expected) {Api.require(expected!=null&&expected>0,400,"投诉版本必填且须为正整数");Api.require(Store.id(before,"version")==expected,409,"投诉已更新，请刷新当前版本");}
    private Map<String,Object> snapshot(Map<String,Object> row) {return Map.of("status",row.get("status"),"version",row.get("version"));}
    private void notifyOwner(Map<String,Object> complaint,String type,String title,String content,LocalDateTime now) {db.update("INSERT INTO notifications(user_id,type,title,content,complaint_id,created_at) VALUES(?,?,?,?,?,?)",complaint.get("user_id"),type,title,content,complaint.get("id"),Timestamp.valueOf(now));}
    private String text(String raw,int max,String label) {String text=raw==null?null:raw.trim();Api.require(text!=null&&!text.isEmpty()&&text.length()<=max,400,label+"须为1至"+max+"字文本");return text;}
    private String hash(long actor,long id,String type,Map<String,Object> body) {var intent=new TreeMap<String,Object>();intent.put("actor_id",actor);intent.put("complaint_id",id);intent.put("action",type);intent.put("body",body);try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(intent).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private LocalDateTime now() {return LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(ChronoUnit.MICROS);}
}
