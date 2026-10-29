package cn.studyroom;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlotTest {
    private Clock clock(String instant) { return Clock.fixed(Instant.parse(instant),ZoneId.of("Asia/Shanghai")); }
    @Test void adjacentIntervalsAreAllowedButPartialOverlapIsNot() {
        var slot=Slot.parse("2026-10-30","09:00","11:00");
        assertFalse(slot.overlaps(660,720));assertTrue(slot.overlaps(630,720));assertTrue(slot.overlaps(510,570));
    }
    @Test void rejectsMalformedReversedShortAndNonMinuteTimes() {
        assertThrows(Api.Failure.class,()->Slot.parse("2026-02-30","09:00","11:00"));
        assertThrows(Api.Failure.class,()->Slot.parse("2026-10-30","11:00","09:00"));
        assertThrows(Api.Failure.class,()->Slot.parse("2026-10-30","09:01","09:30"));
        assertThrows(Api.Failure.class,()->Slot.parse("2026-10-30","09:00:01","11:00"));
        assertThrows(Api.Failure.class,()->Slot.parse("2026-10-30","09:00:00.000001","11:00"));
        assertEquals(30,Slot.parse("2026-10-30","09:01:00.000000","09:31:00").durationMinutes());
    }
    @Test void thirtyAndArbitraryLongContinuousDurationsAreAllowed() {
        for(var end:java.util.List.of("08:30","12:01","13:00","16:30","22:00")) assertNotNull(Slot.parse("2026-10-30","08:00",end));
    }
    @Test void policyUsesCampusDatesAndSubmissionTimeWithoutTenMinuteLead() {
        var policy=new BookingPolicy(clock("2026-10-30T01:00:00Z"));
        policy.validate(Slot.parse("2026-10-30","09:00","09:30"));policy.validate(Slot.parse("2026-10-30","09:01","09:31"));
        assertThrows(Api.Failure.class,()->policy.validate(Slot.parse("2026-10-30","08:59","10:00")));
        policy.validate(Slot.parse("2026-11-01","23:30","24:00"));
        assertThrows(Api.Failure.class,()->policy.validate(Slot.parse("2026-11-02","00:00","00:30")));
        assertThrows(Api.Failure.class,()->policy.validate(Slot.parse("2026-10-29","23:30","24:00")));
        var withSeconds=new BookingPolicy(clock("2026-10-30T01:00:00.001Z"));
        assertThrows(Api.Failure.class,()->withSeconds.validate(Slot.parse("2026-10-30","09:00","09:30")));
    }
    @Test void enforcesCanonicalOpeningBoundaries() {
        var day=new OpeningScheduleService.Day(java.util.List.of(new OpeningScheduleService.Window(480,1260)),java.util.List.of());
        assertTrue(day.contains(Slot.parse("2026-10-30","08:00","09:00")));
        assertTrue(day.contains(Slot.parse("2026-10-30","20:00","21:00")));
        assertFalse(day.contains(Slot.parse("2026-10-30","20:00","21:30")));
    }
    @Test void midnightEndIsSameDayRightBoundaryAndHistoryPrecisionIsPreserved() {
        var slot=Slot.parse("2026-10-30","23:30","24:00:00.000");
        assertEquals(30,slot.durationMinutes());assertEquals("24:00",slot.endText());assertEquals(LocalDateTime.parse("2026-10-31T00:00"),slot.endsAt());
        for(var times:java.util.List.of(new String[]{"23:31","24:00"},new String[]{"24:00","24:00"},new String[]{"23:30","24:01"},new String[]{"22:00","02:00"})) assertThrows(Api.Failure.class,()->Slot.parse("2026-10-30",times[0],times[1]));
        LocalDate date=LocalDate.of(2026,10,30);
        assertEquals(date.atStartOfDay(),Slot.endpoint(date,"00:00:00"));
        assertEquals(date.atTime(9,0,0,123456000),Slot.endpoint(date,"09:00:00.123456"));
        assertEquals(date.plusDays(1).atStartOfDay(),Slot.endpoint(date,"24:00:00.000000"));
    }
    @Test void policyCrossesMidnightInShanghaiEvenWhenClockUsesUtc() {
        var policy=new BookingPolicy(Clock.fixed(Instant.parse("2026-10-29T16:00:00Z"),ZoneOffset.UTC));
        assertEquals("2026-10-30",policy.view().get("campus_today"));
        assertEquals(java.util.List.of("2026-10-30","2026-10-31","2026-11-01"),policy.view().get("dates"));
        assertTrue(policy.view().get("server_now").toString().endsWith("+08:00"));
    }
}
