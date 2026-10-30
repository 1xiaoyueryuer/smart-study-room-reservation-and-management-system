package cn.studyroom;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;

/** Pure projections of immutable review and original-batch recalculation facts. */
@Service
public class AppealReviewProjection {
    private final Store db;private final ObjectMapper json;
    public AppealReviewProjection(Store db,ObjectMapper json){this.db=db;this.json=json;}
    Map<String,Object> review(long appeal,boolean privateFields){
        var rows=db.list("SELECT r.*,COALESCE(NULLIF(u.real_name,''),u.username) AS reviewer_name FROM appeal_reviews r JOIN users u ON u.id=r.actor_id WHERE r.appeal_id=?",appeal);if(rows.isEmpty())return null;var row=rows.get(0);var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","decision","reason","violation_action"))result.put(key,row.get(key));result.put("reviewed_at",AttachmentService.offset(row.get("reviewed_at")));result.put("reviewer_display_name",row.get("reviewer_name"));result.put("verification_source",row.get("source"));result.put("automatic_recalculations",recalculations(Store.id(row,"id")));if(privateFields){result.put("verification_note",row.get("verification_note"));result.put("facts_confirmed",row.get("facts_confirmed"));}return result;
    }
    List<Map<String,Object>> recalculations(long review){
        var rows=db.list("SELECT restriction_id,status_before,status_after,outcome,valid_count_before,valid_count_after,threshold,evaluated_at,window_start,window_end,starts_at,ends_at,source_violations FROM restriction_recalculations WHERE review_id=? ORDER BY restriction_id",review);for(var row:rows){for(String key:List.of("evaluated_at","window_start","window_end","starts_at","ends_at"))row.put(key,AttachmentService.offset(row.get(key)));row.put("source_violations",read(row.get("source_violations").toString()));}return rows;
    }
    Map<String,Object> effects(long review){var row=db.one("SELECT violation_action,booking_effects FROM appeal_reviews WHERE id=?",review);var result=new LinkedHashMap<String,Object>();result.put("violation_action",row.get("violation_action"));result.put("automatic_recalculations",recalculations(review));result.put("booking_effects",row.get("booking_effects")==null?null:read(row.get("booking_effects").toString()));return result;}
    Object read(String value){try{return json.readValue(value,new TypeReference<Object>(){});}catch(Exception e){throw new IllegalStateException("审核历史快照无法读取，请核查数据",e);}}
}
