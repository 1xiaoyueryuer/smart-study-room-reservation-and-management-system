package cn.studyroom;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 反馈事实来自事件；这里只计算同一时钟快照的安全阶段展示，不写状态。 */
@Component
public class ComplaintPhaseProjection {
    private final Store db;
    public ComplaintPhaseProjection(Store db) {this.db=db;}
    void decorate(Map<String,Object> row,LocalDateTime now,boolean admin) {decorate(List.of(row),now,admin);}
    void decorate(List<Map<String,Object>> rows,LocalDateTime now,boolean admin) {
        if(rows.isEmpty()) return;var feedbacks=new HashMap<Long,Map<String,Object>>();var ids=rows.stream().map(row->row.get("id")).toArray();String marks=String.join(",",Collections.nCopies(ids.length,"?"));
        for(var row:db.list("SELECT DISTINCT ON(f.complaint_id) f.complaint_id,f.id,f.feedback_result AS result,f.feedback_basis AS basis,f.created_at AS feedback_at,f.feedback_deadline AS deadline,COALESCE(NULLIF(u.real_name,''),u.username) AS actor_display_name,r.event_type AS response_type,r.created_at AS responded_at FROM complaint_events f LEFT JOIN users u ON u.id=f.actor_id LEFT JOIN complaint_events r ON r.related_feedback_id=f.id WHERE f.event_type='feedback' AND f.complaint_id IN ("+marks+") ORDER BY f.complaint_id,f.id DESC",ids)) {long id=Store.id(row,"complaint_id");row.remove("complaint_id");feedbacks.put(id,row);}
        for(var row:rows) {
            var feedback=feedbacks.get(Store.id(row,"id"));boolean expired=feedback!=null&&!now.isBefore(LocalDateTime.parse(Store.text(feedback,"deadline")));String status=Store.text(row,"status");
            if(feedback!=null) for(String field:List.of("feedback_at","deadline","responded_at")) feedback.put(field,AttachmentService.offset(feedback.get(field)));
            row.put("current_feedback",feedback);row.put("deadline_expired",expired);row.put("server_now",now.atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString());row.put("campus_zone",BookingPolicy.CAMPUS_ZONE.toString());
            row.put("allowed_actions","submitted".equals(status)?List.of("withdraw"):"awaiting_confirmation".equals(status)&&!expired?List.of("confirm","object"):List.of());
            row.put("closure_type","closed".equals(status)&&feedback!=null?feedback.get("response_type"):null);row.put("closed_at","closed".equals(status)&&feedback!=null?feedback.get("responded_at"):null);
            if(admin) row.put("admin_allowed_actions","processing".equals(status)?List.of("feedback"):List.of());
        }
    }
}
