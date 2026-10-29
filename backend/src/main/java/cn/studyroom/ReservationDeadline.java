package cn.studyroom;

import java.time.*;
import java.util.Map;

/** 未签到截止的唯一纯判断，查询、变更保护与事务结算共用。 */
public final class ReservationDeadline {
    public static final int SIGN_IN_MINUTES=15;
    private ReservationDeadline() {}
    public static LocalDateTime at(Map<String,Object> reservation) {
        return Slot.endpoint(LocalDate.parse(Store.text(reservation,"res_date")),Store.text(reservation,"start_time")).plusMinutes(SIGN_IN_MINUTES);
    }
    public static boolean expiredPending(Map<String,Object> reservation,LocalDateTime now) {
        return "pending".equals(reservation.get("status"))&&!now.isBefore(at(reservation));
    }
}
