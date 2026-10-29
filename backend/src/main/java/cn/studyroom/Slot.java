package cn.studyroom;

import java.time.*;

/** 唯一预约区间：同一校园日期的分钟端点，1440只允许作为右开结束。 */
public record Slot(LocalDate date,int startMinute,int endMinute) {
    public static final int MIN_DURATION_MINUTES=30;
    public Slot {
        Api.require(date!=null&&startMinute>=0&&startMinute<1440&&endMinute>0&&endMinute<=1440,400,"预约时间边界无效，24:00仅可作结束");
        Api.require(endMinute-startMinute>=MIN_DURATION_MINUTES,400,"预约须同日且至少30分钟");
    }
    public static Slot parse(String date,String start,String end) {
        try { return new Slot(LocalDate.parse(date),minute(start,false),minute(end,true)); }
        catch(DateTimeException|NullPointerException e) { throw Api.fail(400,"日期或时间格式错误"); }
    }
    private static int minute(String text,boolean end) {
        if(end&&isMidnightEnd(text)) return 1440;
        var time=LocalTime.parse(text);
        Api.require(time.getSecond()==0&&time.getNano()==0,400,"预约时间须为整分钟，不接受非零秒或小数");
        return time.getHour()*60+time.getMinute();
    }
    public String startText() { return format(startMinute); }
    public String endText() { return format(endMinute); }
    public int durationMinutes() { return endMinute-startMinute; }
    public LocalDateTime startsAt() { return date.atStartOfDay().plusMinutes(startMinute); }
    public LocalDateTime endsAt() { return date.atStartOfDay().plusMinutes(endMinute); }
    public boolean overlaps(int otherStart,int otherEnd) { return startMinute<otherEnd&&endMinute>otherStart; }
    public static String format(int minute) { Api.require(minute>=0&&minute<=1440,400,"时间端点无效");return minute==1440?"24:00":String.format("%02d:%02d",minute/60,minute%60); }
    /** 历史TIME端点保留真实秒/小数；不调用新预约精度/时长校验。 */
    public static LocalDateTime endpoint(LocalDate date,String rawTime) {
        return isMidnightEnd(rawTime)?date.plusDays(1).atStartOfDay():date.atTime(LocalTime.parse(rawTime));
    }
    private static boolean isMidnightEnd(String text) { return text!=null&&text.matches("24:00(?::00(?:\\.0{1,9})?)?"); }
}
