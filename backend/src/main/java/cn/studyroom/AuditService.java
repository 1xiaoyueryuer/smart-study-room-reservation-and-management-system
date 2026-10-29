package cn.studyroom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 调用者只传入本次允许审计的业务字段；严禁传入请求体或账号整行。 */
@Service
public class AuditService {
    private static final Set<String> SENSITIVE = Set.of("password", "passwordhash", "token", "accesstoken", "refreshtoken", "authorization", "jwtsecret", "secret", "credential");
    private final Store db;
    private final ObjectMapper json;
    private final Clock clock;
    public AuditService(Store db, ObjectMapper json, Clock clock) { this.db=db; this.json=json; this.clock=clock; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void record(Long actorId, String action, String objectType, String objectId,
                       String reason, Map<String,?> before, Map<String,?> after) {
        rejectSensitive(before); rejectSensitive(after);
        try {
            db.update("INSERT INTO audit_events(actor_id,action,object_type,object_id,reason,before_values,after_values,occurred_at) VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?)",
                actorId, action, objectType, objectId, reason, json.writeValueAsString(before), json.writeValueAsString(after), Timestamp.from(clock.instant()));
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("审计变化不能序列化", e); }
    }

    private static void rejectSensitive(Object value) {
        if (value instanceof Map<?,?> fields) {
            for (var field : fields.entrySet()) {
                String name = field.getKey().toString().replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
                if (SENSITIVE.contains(name)) throw new IllegalArgumentException("审计禁止保存凭证字段");
                rejectSensitive(field.getValue());
            }
        } else if (value instanceof Iterable<?> items) items.forEach(AuditService::rejectSensitive);
    }
}
