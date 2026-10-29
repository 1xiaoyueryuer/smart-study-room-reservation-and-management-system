package cn.studyroom;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import static org.junit.jupiter.api.Assertions.*;

/** 必须使用独立的真实 PostgreSQL 测试库；不会清空开发库。 */
@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
class BookingIntegrationTest {
    @Autowired Store db;
    @Autowired BookingService bookings;
    @Autowired ResourceService resources;
    @Autowired OpeningScheduleService opening;
    @Autowired StatsService stats;
    @Autowired Environment env;
    @Autowired AuditService audit;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    long u1,u2,admin,room,s1,s2;
    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(Instant.parse("2026-10-30T01:00:00Z"),ZoneId.of("Asia/Shanghai")); }
    }
    @BeforeEach void setup() {
        assertTrue(env.getProperty("spring.datasource.url","").endsWith("/studyroom_java_test"),"必须使用独立测试库");
        for(String table:List.of("violations","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","notifications","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);
        u1=db.insert("INSERT INTO users(username,password_hash) VALUES('test1','unused')");
        u2=db.insert("INSERT INTO users(username,password_hash) VALUES('test2','unused')");
        admin=db.insert("INSERT INTO users(username,password_hash,role) VALUES('resource-admin','unused','admin')");
        room=Store.id(resources.save(admin,null,new Requests.Room("测试空间","测试楼",null,null,1,2,"测试数据",null,null,true,null,"测试空间初始化")),"id");
        resources.batch(admin,room,new Requests.Batch(1,2,1L,"测试批量座位"));
        db.update("DELETE FROM audit_events");
        var seats=db.list("SELECT * FROM seats ORDER BY id");s1=Store.id(seats.get(0),"id");s2=Store.id(seats.get(1),"id");
    }
    private Requests.Seat seatRequest(long id,String zone,boolean power,boolean window,String status) {
        var seat=db.one("SELECT * FROM seats WHERE id=?",id);
        return new Requests.Seat(zone,power,window,status,Store.text(seat,"seat_no"),((Number)seat.get("row_no")).intValue(),((Number)seat.get("col_no")).intValue(),Store.id(seat,"version"),"测试维护座位");
    }
    private Requests.Reservation request(long seat,String start,String end) { return new Requests.Reservation(seat,"2026-10-30",start,end); }
    @Test void twentyConcurrentUsersCannotReserveOverlappingSeat() throws Exception {
        var ids=new ArrayList<Long>();for(int i=0;i<20;i++) ids.add(db.insert("INSERT INTO users(username,password_hash) VALUES(?,'unused')","concurrent"+i));
        var results=race(20,i->bookings.create(ids.get(i),request(s1,i%2==0?"10:00":"10:30",i%2==0?"12:00":"12:30")));
        assertEquals(1,results.stream().filter(x->x==200).count());assertEquals(19,results.stream().filter(x->x==409).count());
        assertEquals(1,db.count("SELECT count(*) FROM reservations"));assertEquals(1,db.count("SELECT count(*) FROM notifications"));
        assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.create'"));
    }
    @Test void sameUserCannotConcurrentlyBookDifferentSeats() throws Exception {
        var results=race(12,i->bookings.create(u1,request(i%2==0?s1:s2,"10:00","11:00")));
        assertEquals(1,results.stream().filter(x->x==200).count());assertEquals(11,results.stream().filter(x->x==409).count());
    }
    @Test void adjacentSlotsWorkAndCancelledSlotIsReleased() {
        long id=Store.id(bookings.create(u1,request(s1,"10:00","11:00")),"id");
        assertNotNull(bookings.create(u2,request(s1,"11:00","12:00")));
        bookings.action(u1,id,"cancel");assertNotNull(bookings.create(u1,request(s1,"10:00","11:00")));
    }
    @Test void ownershipAndRepeatedCheckoutCannotCreateNewCredit() {
        long id=Store.id(bookings.create(u1,request(s1,"10:00","11:00")),"id");
        assertEquals(403,assertThrows(Api.Failure.class,()->bookings.action(u2,id,"cancel")).status);
        db.update("UPDATE reservations SET start_time=TIME '09:00',end_time=TIME '10:00' WHERE id=?",id);
        bookings.action(u1,id,"checkin");bookings.action(u1,id,"checkout");
        assertEquals(409,assertThrows(Api.Failure.class,()->bookings.action(u1,id,"checkout")).status);
        assertEquals(0,db.count("SELECT count(*) FROM credit_logs WHERE reservation_id=?",id));
    }
    @Test void repeatedTimeoutTicksCountOneViolation() {
        long id=Store.id(bookings.create(u1,request(s1,"10:00","11:00")),"id");
        db.update("UPDATE reservations SET start_time=TIME '08:00',end_time=TIME '09:00' WHERE id=?",id);
        bookings.tick();bookings.tick();
        assertEquals("unused_ended",db.one("SELECT status FROM reservations WHERE id=?",id).get("status"));
        assertEquals(1,db.count("SELECT count(*) FROM violations WHERE reservation_id=? AND type='no_show'",id));
        assertEquals(0,db.count("SELECT count(*) FROM credit_logs WHERE reservation_id=?",id));
        assertEquals(100,db.count("SELECT credit_score FROM users WHERE id=?",u1));
        assertEquals(0.0,((Number)stats.overview().get("today_utilization")).doubleValue());
    }
    @Test void retiredWaitlistNeverPromotesAndHistoricalPreferencesRemainCancellable() {
        resources.updateSeat(admin,s2,seatRequest(s2,"quiet",true,true,"available"));
        long held=Store.id(bookings.create(u1,request(s2,"10:00","11:00")),"id");
        long waiting=db.insert("INSERT INTO waitlist(user_id,room_id,res_date,start_time,end_time,zone,has_power,near_window) VALUES(?,?,'2026-10-30','10:00','11:00','quiet',true,true)",u2,room);
        assertEquals(410,assertThrows(Api.Failure.class,()->bookings.join(u2,new Requests.Wait(room,"2026-10-30","10:00","11:00","quiet",true,true))).status);
        bookings.tick();bookings.action(u1,held,"cancel");bookings.tick();bookings.tick();
        var row=bookings.waitMine(u2).get(0);assertEquals("waiting",row.get("status"));assertEquals("quiet",row.get("zone"));assertEquals(true,row.get("has_power"));
        assertEquals(0,db.count("SELECT count(*) FROM reservations WHERE user_id=?",u2));
        bookings.cancelWait(u2,waiting);assertEquals("cancelled",db.one("SELECT status FROM waitlist WHERE id=?",waiting).get("status"));
        assertEquals(409,assertThrows(Api.Failure.class,()->bookings.cancelWait(u2,waiting)).status);
    }
    @Test void blockedUserAndMaintenanceSeatCannotBeBooked() {
        db.update("UPDATE users SET credit_banned_until=TIMESTAMP '2026-11-01 00:00:00' WHERE id=?",u1);
        assertEquals(403,assertThrows(Api.Failure.class,()->bookings.create(u1,request(s1,"10:00","11:00"))).status);
        resources.updateSeat(admin,s1,seatRequest(s1,"regular",false,false,"maintenance"));
        assertEquals(409,assertThrows(Api.Failure.class,()->bookings.create(u2,request(s1,"10:00","11:00"))).status);
        assertEquals(0,db.count("SELECT count(*) FROM reservations"));assertEquals(0,db.count("SELECT count(*) FROM notifications"));
    }
    @Test void resourceChangesPreserveExistingReservationsAndHistory() {
        bookings.create(u1,request(s1,"10:00","11:00"));
        assertThrows(Api.Failure.class,()->resources.updateSeat(admin,s1,seatRequest(s1,"regular",false,false,"disabled")));
        assertThrows(Api.Failure.class,()->opening.save(admin,room,new Requests.OpeningChange(null,"2026-10-30",List.of(new Requests.OpeningWindow("12:00","22:00")),Store.id(resources.room(room),"version"),"测试缩短开放")));
        assertThrows(Api.Failure.class,()->resources.deleteRoom(admin,room,new Requests.ResourceDelete(Store.id(resources.room(room),"version"),"测试删除")));
        assertEquals("available",db.one("SELECT status FROM seats WHERE id=?",s1).get("status"));
    }
    @Test void auditSharesBusinessCommitAndRollback() {
        transactions.executeWithoutResult(tx -> {
            db.update("UPDATE users SET real_name='已更新' WHERE id=?", u1);
            audit.record(u1,"update","user",Long.toString(u1),"测试更新",Map.of("real_name","未命名"),Map.of("real_name","已更新"));
        });
        assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE actor_id=?",u1));
        assertThrows(IllegalStateException.class,()->transactions.executeWithoutResult(tx -> {
            db.update("UPDATE users SET real_name='不应保存' WHERE id=?",u1);
            audit.record(u1,"update","user",Long.toString(u1),"回滚测试",Map.of(),Map.of("real_name","不应保存"));
            throw new IllegalStateException("故障");
        }));
        assertEquals("已更新",db.one("SELECT real_name FROM users WHERE id=?",u1).get("real_name"));
        assertEquals(1,db.count("SELECT count(*) FROM audit_events"));
        assertThrows(RuntimeException.class,()->transactions.executeWithoutResult(tx -> {
            db.update("UPDATE users SET real_name='审计失败不应保存' WHERE id=?",u1);
            audit.record(u1,"update","user",Long.toString(u1),"",Map.of(),Map.of());
        }));
        assertEquals("已更新",db.one("SELECT real_name FROM users WHERE id=?",u1).get("real_name"));
    }
    @Test void auditRejectsCredentialsAndRequiresTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->audit.record(u1,"update","user","1","test",Map.of(),Map.of()));
        assertThrows(IllegalArgumentException.class,()->transactions.executeWithoutResult(tx -> audit.record(u1,"update","user","1","test",Map.of(),Map.of("nested",List.of(Map.of("password_hash","secret"))))));
        assertEquals(0,db.count("SELECT count(*) FROM audit_events"));
    }
    private interface Work { void run(int n); }
    private List<Integer> race(int n,Work work) throws Exception {
        var pool=Executors.newFixedThreadPool(n);var start=new CountDownLatch(1);var futures=new ArrayList<Future<Integer>>();
        try {
            for(int i=0;i<n;i++) { final int k=i;futures.add(pool.submit(()->{ start.await();try { work.run(k);return 200; }catch(Api.Failure e) { return e.status; } })); }
            start.countDown();var codes=new ArrayList<Integer>();for(var future:futures) codes.add(future.get(30,TimeUnit.SECONDS));return codes;
        } finally { pool.shutdownNow(); }
    }
}
