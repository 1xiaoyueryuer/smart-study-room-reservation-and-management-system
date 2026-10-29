package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestContractTest {
    private final ObjectMapper json=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    @Test void originalVueSnakeCaseRequestsBindRecordConstructors() throws Exception {
        var reservation=json.readValue("{\"seat_id\":12,\"date\":\"2026-10-30\",\"start_time\":\"10:00\",\"end_time\":\"11:00\"}",Requests.Reservation.class);
        assertEquals(12,reservation.seatId());assertEquals("10:00",reservation.startTime());
        var room=json.readValue("{\"name\":\"test\",\"open_time\":\"08:00\",\"close_time\":\"22:00\",\"seat_rows\":2,\"seat_cols\":3}",Requests.Room.class);
        assertEquals(2,room.seatRows());assertEquals("22:00",room.closeTime());
        var allocation=json.readValue("{\"room_id\":1,\"top_n\":3,\"auto_book\":true,\"has_power\":true}",Requests.Allocation.class);
        assertTrue(allocation.autoBook());assertTrue(allocation.hasPower());assertEquals(3,allocation.topN());
        var registration=json.readValue("{\"username\":\"test\",\"password\":\"123456\",\"real_name\":\"测试\",\"student_no\":\"001\"}",Requests.Register.class);
        assertEquals("测试",registration.realName());assertEquals("001",registration.studentNo());
    }
}
