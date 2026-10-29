package cn.studyroom;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class BookingPolicy {
    public static final ZoneId CAMPUS_ZONE=ZoneId.of("Asia/Shanghai");
    public static final int MAX_ADVANCE_DAYS=2;
    private final Clock clock;
    public BookingPolicy(Clock clock) { this.clock=clock; }
    private ZonedDateTime now() { return clock.instant().atZone(CAMPUS_ZONE); }
    public Map<String,Object> view() {
        var now=now();var today=now.toLocalDate();
        return Map.of("server_now",now.toOffsetDateTime().toString(),"campus_today",today.toString(),"dates",java.util.stream.IntStream.rangeClosed(0,MAX_ADVANCE_DAYS).mapToObj(i->today.plusDays(i).toString()).toList(),"campus_zone",CAMPUS_ZONE.getId(),"min_duration_minutes",Slot.MIN_DURATION_MINUTES);
    }
    public void validate(Slot slot) {
        var now=now();var today=now.toLocalDate();
        Api.require(!slot.date().isBefore(today)&&!slot.date().isAfter(today.plusDays(MAX_ADVANCE_DAYS)),400,"仅可预约校园今天至后天");
        Api.require(!slot.startsAt().isBefore(now.toLocalDateTime()),400,"预约开始不能早于提交时刻");
    }
}
