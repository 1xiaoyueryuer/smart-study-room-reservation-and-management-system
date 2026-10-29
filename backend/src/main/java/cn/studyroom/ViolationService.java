package cn.studyroom;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ViolationService {
    private final Store db;
    public ViolationService(Store db) { this.db=db; }
    @Transactional(propagation=Propagation.MANDATORY)
    public long recordUnused(long userId,long reservationId,String type,LocalDateTime occurredAt,LocalDateTime registeredAt,String reason) {
        return db.insert("INSERT INTO violations(user_id,reservation_id,type,event_key,occurred_at,registered_at,reason,source) VALUES(?,?,?,?,?,?,?,'automatic')",
            userId,reservationId,type,"unused:"+reservationId,Timestamp.valueOf(occurredAt),Timestamp.valueOf(registeredAt),reason);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> mine(long userId) {
        return db.list("SELECT id,reservation_id,type,occurred_at,registered_at,status,reason,source FROM violations WHERE user_id=? ORDER BY occurred_at DESC,id DESC",userId);
    }
}
