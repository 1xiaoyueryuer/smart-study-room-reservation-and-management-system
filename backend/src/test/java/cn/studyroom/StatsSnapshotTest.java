package cn.studyroom;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies that all current/trend values share the one captured Clock instant. */
class StatsSnapshotTest {
    @Test void campusMidnightAndSevenDaysUseExactlyOneClockRead() {
        var db=mock(Store.class,call->switch(call.getMethod().getName()) {
            case "list"->List.of();case "count"->0L;default->RETURNS_DEFAULTS.answer(call);
        });
        var auth=mock(AuthService.class);when(auth.profile(7)).thenReturn(Map.of("role","admin","status","active"));
        var clock=mock(Clock.class);when(clock.instant()).thenReturn(Instant.parse("2026-10-30T16:00:00.123456Z"),Instant.parse("2026-11-01T00:00:00Z"));
        var stats=new StatsService(db,clock,mock(OpeningScheduleService.class),auth);
        var result=stats.overview(7,Map.of("date","2026-10-01"));
        verify(clock,times(1)).instant();
        assertEquals("2026-10-31T00:00:00.123456+08:00",result.get("snapshot_at"));
        assertEquals(Map.of("selected_date","2026-10-01","trend_from","2026-10-25","trend_to","2026-10-31"),withoutNullRoom(result));
        @SuppressWarnings("unchecked") var trend=(List<Map<String,Object>>)result.get("trend");
        assertEquals(7,trend.size());assertEquals("2026-10-25",trend.get(0).get("date"));assertEquals("2026-10-31",trend.get(6).get("date"));
        @SuppressWarnings("unchecked") var current=(Map<String,Object>)result.get("current");
        assertEquals(0,current.get("open_available_seats"));assertNull(current.get("usage_ratio"));
    }
    private Map<String,Object> withoutNullRoom(Map<String,Object> result) {
        @SuppressWarnings("unchecked") var scope=new LinkedHashMap<>((Map<String,Object>)result.get("scope"));
        assertTrue(scope.containsKey("room_id"));assertNull(scope.remove("room_id"));return scope;
    }
    @Test void inactiveOrOrdinaryActorCannotReachAnyAggregationQuery() {
        var db=mock(Store.class);var clock=mock(Clock.class);var opening=mock(OpeningScheduleService.class);var auth=mock(AuthService.class);
        var stats=new StatsService(db,clock,opening,auth);
        for(var user:List.of(Map.<String,Object>of("role","admin","status","disabled"),Map.<String,Object>of("role","student","status","active"))) {
            when(auth.profile(7)).thenReturn(user);
            assertEquals(403,assertThrows(Api.Failure.class,()->stats.overview(7,Map.of())).status);
            assertEquals(403,assertThrows(Api.Failure.class,()->stats.heatmap(7,Map.of("room_id","1"))).status);
        }
        verifyNoInteractions(db,clock,opening);
    }
    @Test void usageAfterItsPlannedEndFailsRatherThanReturningFabricatedZero() {
        var corrupt=Map.<String,Object>of("checkin_at","2026-10-30T11:00:00","res_date","2026-10-30",
            "end_time","10:00:00","seat_id",1L,"room_id",1L,"seat_no","01","room_name","Engineering room");
        var db=mock(Store.class,call->{
            if(call.getMethod().getName().equals("list"))return call.getArgument(0,String.class).startsWith("SELECT u.checkin_at")?List.of(corrupt):List.of();
            if(call.getMethod().getName().equals("count"))return 0L;
            return RETURNS_DEFAULTS.answer(call);
        });
        var auth=mock(AuthService.class);when(auth.profile(7)).thenReturn(Map.of("role","admin","status","active"));
        var stats=new StatsService(db,Clock.fixed(Instant.parse("2026-10-30T04:00:00Z"),BookingPolicy.CAMPUS_ZONE),mock(OpeningScheduleService.class),auth);
        assertThrows(IllegalStateException.class,()->stats.overview(7,Map.of()));
    }
}
