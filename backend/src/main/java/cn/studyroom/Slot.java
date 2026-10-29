package cn.studyroom;

import java.time.*;

/** 迁移阶段保留原规则；课程需求的 D+2、4h 额度等将在后续功能阶段替换。 */
public record Slot(LocalDate date,LocalTime start,LocalTime end) {
    public static Slot parse(String date,String start,String end) {
        try {
            Slot slot=new Slot(LocalDate.parse(date),LocalTime.parse(start),LocalTime.parse(end));
            Api.require(slot.end.isAfter(slot.start),400,"结束时间必须晚于开始时间");
            Api.require(slot.start.getMinute()%30==0&&slot.end.getMinute()%30==0&&slot.start.getSecond()==0&&slot.end.getSecond()==0,400,"预约时间需为半小时整点");
            Api.require(Duration.between(slot.start,slot.end).toMinutes()<=480,400,"单次预约最长8小时");
            return slot;
        } catch(DateTimeException|NullPointerException e) { throw Api.fail(400,"日期或时间格式错误"); }
    }
    public LocalDateTime startsAt() { return date.atTime(start); }
    public LocalDateTime endsAt() { return date.atTime(end); }
    public void validateFuture(Clock clock) { Api.require(!startsAt().isBefore(LocalDateTime.now(clock).plusMinutes(10)),400,"请至少提前10分钟预约"); }
    public boolean overlaps(LocalTime otherStart,LocalTime otherEnd) { return start.isBefore(otherEnd)&&end.isAfter(otherStart); }
}
