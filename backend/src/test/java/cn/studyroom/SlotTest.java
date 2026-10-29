package cn.studyroom;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlotTest {
    @Test void adjacentIntervalsAreAllowedButPartialOverlapIsNot() {
        var slot=Slot.parse("2026-10-30","09:00","11:00");
        assertFalse(slot.overlaps(LocalTime.of(11,0),LocalTime.of(12,0)));
        assertTrue(slot.overlaps(LocalTime.of(10,30),LocalTime.of(12,0)));
        assertTrue(slot.overlaps(LocalTime.of(8,30),LocalTime.of(9,30)));
    }
    @Test void rejectsMalformedReversedAndOffGridTimes() {
        assertThrows(Api.Failure.class,()->Slot.parse("2026-02-30","09:00","11:00"));
        assertThrows(Api.Failure.class,()->Slot.parse("2026-10-30","11:00","09:00"));
        assertThrows(Api.Failure.class,()->Slot.parse("2026-10-30","09:01","11:00"));
        assertThrows(Api.Failure.class,()->Slot.parse("2026-10-30","09:00:01","11:00"));
    }
    @Test void enforcesEightHourLimit() {
        assertNotNull(Slot.parse("2026-10-30","08:00","16:00"));
        assertThrows(Api.Failure.class,()->Slot.parse("2026-10-30","08:00","16:30"));
    }
    @Test void enforcesLeadTimeAtBoundary() {
        var slot=Slot.parse("2026-10-30","09:00","10:00");
        slot.validateFuture(Clock.fixed(Instant.parse("2026-10-30T00:50:00Z"),ZoneId.of("Asia/Shanghai")));
        assertThrows(Api.Failure.class,()->slot.validateFuture(Clock.fixed(Instant.parse("2026-10-30T00:50:01Z"),ZoneId.of("Asia/Shanghai"))));
    }
    @Test void enforcesCanonicalOpeningBoundaries() {
        var day=new OpeningScheduleService.Day(java.util.List.of(new OpeningScheduleService.Window(480,1260)),java.util.List.of());
        assertTrue(day.contains(Slot.parse("2026-10-30","08:00","09:00")));
        assertTrue(day.contains(Slot.parse("2026-10-30","20:00","21:00")));
        assertFalse(day.contains(Slot.parse("2026-10-30","20:00","21:30")));
    }
}
