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
    @Test void enforcesRoomOpeningBoundaries() {
        var room=java.util.Map.<String,Object>of("enabled",true,"open_time","08:00:00","close_time","21:00:00");
        Slot.parse("2026-10-30","08:00","09:00").validateRoom(room);
        Slot.parse("2026-10-30","20:00","21:00").validateRoom(room);
        assertThrows(Api.Failure.class,()->Slot.parse("2026-10-30","20:00","21:30").validateRoom(room));
    }
}
