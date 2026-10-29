package cn.studyroom;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpeningWindowTest {
    Requests.OpeningWindow w(String start,String end) { return new Requests.OpeningWindow(start,end); }
    @Test void adjacentUnsortedWindowsNormalizeWithoutJoiningGaps() {
        var result=OpeningScheduleService.normalize(List.of(w("14:00","16:00"),w("10:00","12:00"),w("08:00","10:00")));
        assertEquals(List.of(new OpeningScheduleService.Window(480,720),new OpeningScheduleService.Window(840,960)),result);
    }
    @Test void overlappingReversedAndInvalidMidnightWindowsAreRejected() {
        assertThrows(Api.Failure.class,()->OpeningScheduleService.normalize(List.of(w("08:00","10:01"),w("10:00","11:00"))));
        assertThrows(Api.Failure.class,()->OpeningScheduleService.normalize(List.of(w("22:00","08:00"))));
        assertThrows(Api.Failure.class,()->OpeningScheduleService.normalize(List.of(w("24:00","24:00"))));
        assertThrows(Api.Failure.class,()->OpeningScheduleService.normalize(List.of(w("08:00:01","10:00"))));
        assertEquals(List.of(new OpeningScheduleService.Window(0,1440)),OpeningScheduleService.normalize(List.of(w("00:00","24:00"))));
    }
    @Test void subtractUsesUnionOfOverlapsAndRespectsTouchingEndpoints() {
        var open=List.of(new OpeningScheduleService.Window(480,840));
        var closed=List.of(new OpeningScheduleService.Window(540,660),new OpeningScheduleService.Window(600,720),new OpeningScheduleService.Window(400,480),new OpeningScheduleService.Window(840,900));
        assertEquals(List.of(new OpeningScheduleService.Window(480,540),new OpeningScheduleService.Window(720,840)),OpeningScheduleService.subtract(open,closed));
    }
}
