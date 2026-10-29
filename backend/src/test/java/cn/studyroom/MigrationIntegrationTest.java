package cn.studyroom;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION", matches="true")
class MigrationIntegrationTest {
    DriverManagerDataSource source;
    String schema;
    @TempDir Path scripts;

    @BeforeEach void setup() throws Exception {
        Properties local = new Properties();
        Path file = Path.of("../.local/application-local.properties");
        if (Files.exists(file)) try (var in = Files.newInputStream(file)) { local.load(in); }
        String url = System.getenv().getOrDefault("STUDYROOM_TEST_DB_URL", "jdbc:postgresql://127.0.0.1:5433/studyroom_java_test");
        source = new DriverManagerDataSource(url,
            System.getenv().getOrDefault("STUDYROOM_DB_USERNAME", local.getProperty("spring.datasource.username", "studyroom")),
            System.getenv().getOrDefault("STUDYROOM_DB_PASSWORD", local.getProperty("spring.datasource.password", "")));
        try (var c=source.getConnection(); var s=c.createStatement(); var r=s.executeQuery("SELECT current_database()")) {
            r.next(); assertEquals("studyroom_java_test", r.getString(1), "禁止操作其他数据库");
        }
        schema="migration_test_"+UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA "+schema);
    }
    @AfterEach void cleanup() throws Exception {
        if (schema!=null) execute("DROP SCHEMA "+schema+" CASCADE");
    }
    Flyway flyway(String... locations) {
        return Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
            .cleanDisabled(true).baselineOnMigrate(false).locations(locations.length==0?new String[]{"classpath:db/migration"}:locations).load();
    }
    void execute(String sql) throws Exception {
        try (var c=source.getConnection(); var s=c.createStatement()) { s.execute(sql); }
    }
    long count(String sql) throws Exception {
        try (var c=source.getConnection(); var s=c.createStatement(); var r=s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }
    void legacy() throws Exception {
        try (var c=source.getConnection(); var s=c.createStatement()) {
            s.execute("SET search_path TO "+schema);
            ScriptUtils.executeSqlScript(c,new org.springframework.core.io.support.EncodedResource(new ClassPathResource("db/migration/V1__postgresql_baseline.sql"), java.nio.charset.StandardCharsets.UTF_8));
            s.execute("INSERT INTO users(username,password_hash) VALUES('legacy','hash'); INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('legacy',1,1); INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(1,'1',1,1); INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time) VALUES(1,1,'2026-10-30','10:00','11:00')");
        }
    }
    @Test void emptyInstallationAndRepeatedStartup() throws Exception {
        var f=flyway(); DatabaseMigrations.migrate(f,false); DatabaseMigrations.migrate(f,false);
        assertEquals(15,count("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".booking_mutex WHERE id=1"));
        assertEquals(0,count("SELECT count(*) FROM "+schema+".audit_events"));
        assertEquals(0,count("SELECT count(*) FROM "+schema+".seat_codes"));
        assertThrows(FlywayException.class,f::clean);
    }
    @Test void legacyRequiresExplicitAdoptionAndPreservesData() throws Exception {
        legacy(); execute("INSERT INTO "+schema+".users(username,password_hash,student_no) VALUES('known-campus','hash','OLD-01')"); var f=flyway();
        assertThrows(IllegalStateException.class,()->DatabaseMigrations.migrate(f,false));
        DatabaseMigrations.migrate(f,true); DatabaseMigrations.migrate(f,false);
        assertEquals(1,count("SELECT count(*) FROM "+schema+".users WHERE username='legacy' AND campus_id IS NULL AND phone IS NULL AND email IS NULL"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".users WHERE username='legacy' AND password_hash='hash'"));
        assertEquals(7,count("SELECT count(*) FROM "+schema+".opening_schedules WHERE room_id=1 AND weekday BETWEEN 1 AND 7"));
        assertEquals(7,count("SELECT count(*) FROM "+schema+".opening_windows WHERE start_minute=480 AND end_minute=1320"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".rooms WHERE name='legacy' AND enabled AND version=1 AND building IS NULL AND floor IS NULL AND resource_reason IS NULL"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".seats WHERE version=1 AND resource_reason IS NULL"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".reservations WHERE user_id=1 AND seat_id=1 AND status='pending'"));
        assertEquals(0,count("SELECT count(*) FROM "+schema+".seat_codes"),"历史座位不能被伪造成已经配置真实贴码");
        assertEquals(15,count("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".users WHERE username='known-campus' AND campus_id='OLD-01' AND student_no='OLD-01'"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE type='BASELINE'"));
    }
    @Test void oldViolationIsHistoricalUnusedEndWithoutInventedIndependentPenalty() throws Exception {
        legacy();execute("UPDATE "+schema+".reservations SET status='violation',source='auto'; UPDATE "+schema+".users SET credit_score=84,credit_banned_until=TIMESTAMP '2026-11-01 12:34:56'; INSERT INTO "+schema+".credit_logs(user_id,delta,reason,reservation_id) VALUES(1,-8,'legacy evidence',1)");
        DatabaseMigrations.migrate(flyway(),true);
        assertEquals(1,count("SELECT count(*) FROM "+schema+".reservations WHERE status='unused_ended' AND legacy_status='violation' AND termination_reason='legacy_violation' AND ended_at IS NULL AND source='auto'"));
        assertEquals(0,count("SELECT count(*) FROM "+schema+".violations"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".users WHERE credit_score=84 AND credit_banned_until=TIMESTAMP '2026-11-01 12:34:56'"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".credit_logs WHERE delta=-8 AND reason='legacy evidence'"));
    }
    @Test void legacyNonMinuteTimeRefusesMigrationWithoutChangingTrueValues() throws Exception {
        legacy();execute("UPDATE "+schema+".rooms SET open_time=TIME '08:00:30'");
        var failure=assertThrows(FlywayException.class,()->DatabaseMigrations.migrate(flyway(),true));
        assertTrue(failure.getMessage().contains("非整分钟"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".rooms WHERE open_time=TIME '08:00:30'"));
        assertEquals(0,count("SELECT count(*) FROM information_schema.tables WHERE table_schema='"+schema+"' AND table_name='opening_schedules'"));
        assertEquals(0,count("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE version='6'"));
    }
    @Test void unknownStructureRejectedWithoutBaseline() throws Exception {
        legacy(); execute("ALTER TABLE "+schema+".users ADD COLUMN unrecognized text");
        var error=assertThrows(IllegalStateException.class,()->DatabaseMigrations.migrate(flyway(),true));
        assertTrue(error.getMessage().contains("拒绝接管"));
        assertEquals(0,count("SELECT count(*) FROM information_schema.tables WHERE table_schema='"+schema+"' AND table_name='flyway_schema_history'"));
        assertEquals(0,count("SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 'baseline_check_%'"));
    }
    void usageBaseline() throws Exception {
        legacy();var before=Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema).cleanDisabled(true).baselineOnMigrate(false).baselineVersion("1").target("8").locations("classpath:db/migration").load();DatabaseMigrations.migrate(before,true);
    }
    @Test void usageMigrationPreserves546HistoriesFractionalEarlyCheckinsReasonsAndTempCutover() throws Exception {
        usageBaseline();execute("DELETE FROM "+schema+".reservations");
        execute("INSERT INTO "+schema+".reservations(user_id,seat_id,res_date,start_time,end_time,status,checkin_at,checkout_at) SELECT 1,1,CURRENT_DATE-1-(i%14),TIME '10:00',TIME '11:00','completed',(CURRENT_DATE-1-(i%14))+TIME '09:59:59.5',(CURRENT_DATE-1-(i%14))+TIME '11:00:03.123456' FROM generate_series(1,546) i");
        java.math.BigDecimal oldSeconds;try(var c=source.getConnection();var q=c.createStatement();var result=q.executeQuery("SELECT SUM(EXTRACT(epoch FROM LEAST(checkout_at,res_date+end_time)-checkin_at)) FROM "+schema+".reservations")) { result.next();oldSeconds=result.getBigDecimal(1); }
        execute("INSERT INTO "+schema+".reservations(user_id,seat_id,res_date,start_time,end_time,status,checkin_at,leave_at) VALUES(1,1,(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date,'00:00','24:00','temp_leave',(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')-INTERVAL '1 hour',(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')-INTERVAL '40 minutes'),(1,1,CURRENT_DATE-1,'23:00','24:00','temp_leave',(CURRENT_DATE-1)+TIME '23:00:00.123456',(CURRENT_DATE-1)+TIME '23:10')");
        execute("UPDATE "+schema+".reservations SET termination_reason='temp_leave_timeout',ended_at=res_date+end_time WHERE id=(SELECT min(id) FROM "+schema+".reservations WHERE status='completed'); UPDATE "+schema+".reservations SET checkout_at=res_date+end_time WHERE termination_reason='temp_leave_timeout'");
        DatabaseMigrations.migrate(flyway(),false);
        assertEquals(548,count("SELECT count(*) FROM "+schema+".usage_records WHERE origin='legacy'"));
        try(var c=source.getConnection();var q=c.createStatement();var result=q.executeQuery("SELECT SUM(EXTRACT(epoch FROM u.ended_at-u.checkin_at)) FROM "+schema+".usage_records u JOIN "+schema+".reservations r ON r.id=u.reservation_id WHERE r.legacy_status IS NULL")) { result.next();assertEquals(0,oldSeconds.compareTo(result.getBigDecimal(1))); }
        assertEquals(545,count("SELECT count(*) FROM "+schema+".usage_records WHERE end_reason='legacy_unclassified'"));assertEquals(1,count("SELECT count(*) FROM "+schema+".usage_records WHERE end_reason='temp_leave_timeout' AND legacy_termination_reason='temp_leave_timeout'"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".reservations r JOIN "+schema+".usage_records u ON u.reservation_id=r.id WHERE r.legacy_status='temp_leave' AND r.status='checked_in' AND u.ended_at IS NULL AND r.leave_at IS NOT NULL"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".reservations r JOIN "+schema+".usage_records u ON u.reservation_id=r.id WHERE r.legacy_status='temp_leave' AND r.status='completed' AND u.ended_at=r.res_date+r.end_time AND u.end_reason='auto_end' AND u.checkin_at=r.checkin_at AND r.checkout_at IS NULL"));
        assertEquals(0,count("SELECT count(*) FROM "+schema+".violations"));assertEquals(0,count("SELECT count(*) FROM "+schema+".credit_logs"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"completed_missing_time","active_missing_checkin","active_has_checkout","checkout_without_checkin","state_time_conflict","checkout_before_checkin","checkin_not_before_scheduled_end","active_future_checkin","active_has_termination","ended_checkout_conflict","unknown_termination_reason"})
    void inconsistentHistoricalUsageRejectsMigrationWithoutChangingFacts(String problem) throws Exception {
        usageBaseline();execute("UPDATE "+schema+".reservations SET res_date=CURRENT_DATE-1,start_time='10:00',end_time='11:00',status='completed',checkin_at=(CURRENT_DATE-1)+TIME '10:00',checkout_at=(CURRENT_DATE-1)+TIME '11:00'");
        String change=switch(problem) {
            case "completed_missing_time" -> "checkin_at=NULL,checkout_at=NULL";
            case "active_missing_checkin" -> "status='checked_in',checkin_at=NULL,checkout_at=NULL";
            case "active_has_checkout" -> "status='checked_in'";
            case "checkout_without_checkin" -> "status='pending',checkin_at=NULL";
            case "state_time_conflict" -> "status='cancelled'";
            case "checkout_before_checkin" -> "checkout_at=(CURRENT_DATE-1)+TIME '09:59'";
            case "checkin_not_before_scheduled_end" -> "checkin_at=(CURRENT_DATE-1)+TIME '11:00',checkout_at=(CURRENT_DATE-1)+TIME '12:00'";
            case "active_future_checkin" -> "status='checked_in',res_date=CURRENT_DATE+1,checkin_at=(CURRENT_DATE+1)+TIME '10:00',checkout_at=NULL";
            case "active_has_termination" -> "status='checked_in',checkout_at=NULL,ended_at=(CURRENT_DATE-1)+TIME '11:00'";
            case "ended_checkout_conflict" -> "ended_at=(CURRENT_DATE-1)+TIME '10:59'";
            case "unknown_termination_reason" -> "termination_reason='unconfirmed_reason'";
            default -> throw new AssertionError(problem);
        };
        execute("UPDATE "+schema+".reservations SET "+change);
        String raw;try(var c=source.getConnection();var q=c.createStatement();var result=q.executeQuery("SELECT row_to_json(r)::text FROM "+schema+".reservations r")) { result.next();raw=result.getString(1); }
        var failure=assertThrows(FlywayException.class,()->DatabaseMigrations.migrate(flyway(),false));assertTrue(failure.getMessage().contains(problem));
        assertEquals(0,count("SELECT count(*) FROM information_schema.tables WHERE table_schema='"+schema+"' AND table_name='usage_records'"));assertEquals(8,count("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE success"));
        try(var c=source.getConnection();var q=c.createStatement();var result=q.executeQuery("SELECT row_to_json(r)::text FROM "+schema+".reservations r")) { result.next();assertEquals(raw,result.getString(1)); }
    }
    @Test void submittedComplaintUpgradePreservesOriginalFactsAndDoesNotInventAcceptanceOrReminder() throws Exception {
        var previous=Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema).cleanDisabled(true).baselineOnMigrate(false).target("11").locations("classpath:db/migration").load();DatabaseMigrations.migrate(previous,false);
        execute("INSERT INTO "+schema+".users(username,password_hash) VALUES('s14-owner','hash'); INSERT INTO "+schema+".complaints(user_id,operation_id,request_hash,type,status,description,submitted_at) VALUES(1,'55555555-5555-4555-8555-555555555555','s14-saved-hash','service','submitted','S14 historical fact',TIMESTAMP '2026-10-29 09:01:02.123456'); INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at) VALUES(1,1,'submitted','user_submit',TIMESTAMP '2026-10-29 09:01:02.123456')");
        DatabaseMigrations.migrate(flyway(),false);assertEquals(1,count("SELECT count(*) FROM "+schema+".complaints WHERE description='S14 historical fact' AND request_hash='s14-saved-hash' AND status='submitted' AND version=1 AND handler_id IS NULL AND accepted_at IS NULL AND submitted_at=TIMESTAMP '2026-10-29 09:01:02.123456'"));assertEquals(1,count("SELECT count(*) FROM "+schema+".complaint_events WHERE event_type='submitted' AND source='user_submit' AND operation_id IS NULL AND occurred_at IS NULL AND subject_user_id IS NULL AND private_note IS NULL AND NOT identity_confirmed AND NOT facts_confirmed AND NOT valid_reservation_confirmed"));assertEquals(0,count("SELECT count(*) FROM "+schema+".reminders"));assertEquals(0,count("SELECT count(*) FROM "+schema+".audit_events"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"identity_basis_null","outcome_null"})
    void verificationDatabaseConstraintsRejectUnknownNullTruthValues(String field) throws Exception {
        DatabaseMigrations.migrate(flyway(),false);execute("INSERT INTO "+schema+".users(username,password_hash,role) VALUES('admin','hash','admin'),('owner','hash','student'),('subject','hash','staff'); INSERT INTO "+schema+".complaints(user_id,operation_id,request_hash,type,status,description,submitted_at,handler_id,accepted_at) VALUES(2,'66666666-6666-4666-8666-666666666666','intent','service','processing','engineering simulation',TIMESTAMP '2026-10-29 09:00',1,TIMESTAMP '2026-10-29 09:01')");
        String outcome=field.equals("outcome_null")?"NULL":"'cooperated'",subject=field.equals("identity_basis_null")?"3":"NULL",identity=field.equals("identity_basis_null")?"true":"false";
        assertThrows(SQLException.class,()->execute("INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,occurred_at,outcome,public_note,private_note,subject_user_id,identity_confirmed,identity_basis) VALUES(1,1,'verification','admin_self_report',TIMESTAMP '2026-10-29 09:02','77777777-7777-4777-8777-777777777777','intent',TIMESTAMP '2026-10-29 09:00',"+outcome+",'public','private',"+subject+","+identity+",NULL)"));assertEquals(0,count("SELECT count(*) FROM "+schema+".complaint_events"));
    }
    @Test void complaintLifecycleUpgradePreservesPrivateHistoryAndSelfReferenceDeletesByStatement() throws Exception {
        var previous=Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema).cleanDisabled(true).baselineOnMigrate(false).target("12").locations("classpath:db/migration").load();DatabaseMigrations.migrate(previous,false);
        execute("INSERT INTO "+schema+".users(username,password_hash,role) VALUES('admin','hash','admin'),('owner','hash','student'),('subject','hash','staff'); INSERT INTO "+schema+".complaints(user_id,operation_id,request_hash,type,status,description,submitted_at,handler_id,accepted_at,version) VALUES(2,'11111111-1111-4111-8111-111111111111','intent','service','processing','old original',TIMESTAMP '2026-10-29 09:00',1,TIMESTAMP '2026-10-29 09:01',3); INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,occurred_at,outcome,public_note,private_note,subject_user_id,identity_confirmed,identity_basis) VALUES(1,1,'verification','admin_self_report',TIMESTAMP '2026-10-29 09:02','22222222-2222-4222-8222-222222222222','old-intent',TIMESTAMP '2026-10-29 09:00','cooperated','public','private preserved',3,true,'identity preserved'); INSERT INTO "+schema+".reminders(event_id,subject_user_id,actor_id,occurred_at,registered_at) VALUES(1,3,1,TIMESTAMP '2026-10-29 09:00',TIMESTAMP '2026-10-29 09:02'); INSERT INTO "+schema+".notifications(user_id,type,title,content) VALUES(2,'old_type','old notification','old content')");
        DatabaseMigrations.migrate(flyway(),false);assertEquals(1,count("SELECT count(*) FROM "+schema+".complaints WHERE status='processing' AND version=3 AND handler_id=1"));assertEquals(1,count("SELECT count(*) FROM "+schema+".complaint_events WHERE private_note='private preserved' AND identity_basis='identity preserved'"));assertEquals(1,count("SELECT count(*) FROM "+schema+".reminders WHERE event_id=1"));assertEquals(1,count("SELECT count(*) FROM "+schema+".notifications WHERE complaint_id IS NULL AND content='old content'"));
        execute("INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,feedback_result,feedback_basis,feedback_deadline) VALUES(1,1,'feedback','admin_self_report',TIMESTAMP '2026-10-29 10:00','33333333-3333-4333-8333-333333333333','new-intent','result','basis',TIMESTAMP '2026-11-05 10:00'); UPDATE "+schema+".complaints SET status='awaiting_confirmation' WHERE id=1; INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at,occurred_at,related_feedback_id) VALUES(1,NULL,'auto_closed','system',TIMESTAMP '2026-11-05 10:01',TIMESTAMP '2026-11-05 10:00',2)");assertEquals(1,count("SELECT count(*) FROM "+schema+".complaints WHERE status='awaiting_confirmation'"));
        execute("DELETE FROM "+schema+".notifications; DELETE FROM "+schema+".reminders; DELETE FROM "+schema+".complaint_events");assertEquals(0,count("SELECT count(*) FROM "+schema+".complaint_events"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"result_null","basis_null","deadline_null","manual_actor_null","system_actor_present","response_without_feedback","cross_complaint"})
    void complaintLifecycleDatabaseConstraintsRejectInvalidFacts(String problem) throws Exception {
        DatabaseMigrations.migrate(flyway(),false);execute("INSERT INTO "+schema+".users(username,password_hash,role) VALUES('admin','hash','admin'),('owner','hash','student'); INSERT INTO "+schema+".complaints(user_id,operation_id,request_hash,type,status,description,submitted_at,handler_id,accepted_at) VALUES(2,'44444444-4444-4444-8444-444444444444','intent','service','processing','engineering simulation',TIMESTAMP '2026-10-29 09:00',1,TIMESTAMP '2026-10-29 09:01'),(2,'55555555-5555-4555-8555-555555555555','intent','service','processing','second simulation',TIMESTAMP '2026-10-29 09:00',1,TIMESTAMP '2026-10-29 09:01')");
        if(problem.equals("cross_complaint")) execute("INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,feedback_result,feedback_basis,feedback_deadline) VALUES(1,1,'feedback','admin_self_report',TIMESTAMP '2026-10-29 10:00','88888888-8888-4888-8888-888888888888','intent','result','basis',TIMESTAMP '2026-11-05 10:00')");
        String sql;if(Set.of("system_actor_present","response_without_feedback","cross_complaint").contains(problem)) sql="INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at,related_feedback_id) VALUES("+(problem.equals("cross_complaint")?"2":"1")+","+(problem.equals("system_actor_present")?"1":"NULL")+",'auto_closed','system',TIMESTAMP '2026-11-05 10:00',"+(problem.equals("response_without_feedback")?"NULL":"1")+")";
        else sql="INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,feedback_result,feedback_basis,feedback_deadline) VALUES(1,"+(problem.equals("manual_actor_null")?"NULL":"1")+",'feedback','admin_self_report',TIMESTAMP '2026-10-29 10:00','99999999-9999-4999-8999-999999999999','intent',"+(problem.equals("result_null")?"NULL":"'result'")+","+(problem.equals("basis_null")?"NULL":"'basis'")+","+(problem.equals("deadline_null")?"NULL":"TIMESTAMP '2026-11-05 10:00'")+")";
        String statement=sql;assertThrows(SQLException.class,()->execute(statement));
    }
    @Test void handlingTargetUpgradeDerivesOnlyKnownServiceDeadlineAndPreservesOldLifecycleFacts() throws Exception {
        var previous=Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema).cleanDisabled(true).baselineOnMigrate(false).target("13").locations("classpath:db/migration").load();DatabaseMigrations.migrate(previous,false);
        execute("INSERT INTO "+schema+".users(username,password_hash,role) VALUES('admin','hash','admin'),('owner','hash','student'); INSERT INTO "+schema+".rooms(name,seat_rows,seat_cols) VALUES('legacy room',1,1); INSERT INTO "+schema+".seats(room_id,seat_no,row_no,col_no) VALUES(1,'legacy',1,1); INSERT INTO "+schema+".reservations(user_id,seat_id,res_date,start_time,end_time,status) VALUES(2,1,'2026-10-01','10:00','11:00','cancelled')");
        execute("INSERT INTO "+schema+".complaints(user_id,operation_id,request_hash,type,status,description,reservation_id,room_id,submitted_at,handler_id,accepted_at) VALUES(2,'a1111111-1111-4111-8111-111111111111','intent','service','processing','active legacy service',NULL,NULL,'2026-10-01 09:00',1,'2026-10-01 09:01'),(2,'a2222222-2222-4222-8222-222222222222','intent','service','closed','late completed legacy service',NULL,NULL,'2026-10-01 09:00',1,'2026-10-01 09:01'),(2,'a3333333-3333-4333-8333-333333333333','intent','occupation','submitted','unknown original opening',1,1,'2026-10-01 09:00',NULL,NULL),(2,'a4444444-4444-4444-8444-444444444444','intent','occupation','processing','accepted unknown opening',1,1,'2026-10-01 09:00',1,'2026-10-01 09:01'),(2,'a5555555-5555-4555-8555-555555555555','intent','service','withdrawn','withdrawn service',NULL,NULL,'2026-10-01 09:00',NULL,NULL)");
        execute("INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at) SELECT id,2,'submitted','user_submit',submitted_at FROM "+schema+".complaints ORDER BY id; INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,feedback_result,feedback_basis,feedback_deadline) VALUES(2,1,'feedback','admin_self_report','2026-10-03 09:00','b1111111-1111-4111-8111-111111111111','intent','old real result','old public basis','2026-10-10 09:00'); INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,related_feedback_id) VALUES(2,2,'confirmed','user_submit','2026-10-03 09:01','b2222222-2222-4222-8222-222222222222','intent',6); INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash) VALUES(5,2,'withdrawn','user_submit','2026-10-02 09:00','b3333333-3333-4333-8333-333333333333','intent')");
        String old;try(var c=source.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT jsonb_build_object('complaints',(SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM "+schema+".complaints c),'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM "+schema+".complaint_events e))::text")){r.next();old=r.getString(1);}
        DatabaseMigrations.migrate(flyway(),false);assertEquals(3,count("SELECT count(*) FROM "+schema+".complaint_targets WHERE kind='first_feedback' AND anchor_at=TIMESTAMP '2026-10-01 09:00' AND due_at=TIMESTAMP '2026-10-02 09:00' AND basis='legacy_service_derived' AND source='legacy_derived' AND room_version IS NULL"));assertEquals(2,count("SELECT count(*) FROM "+schema+".complaint_targets WHERE kind='acceptance' AND calculation_status='legacy_unresolved' AND due_at IS NULL AND anchor_at IS NULL AND room_version IS NULL"));assertEquals(0,count("SELECT count(*) FROM "+schema+".complaint_target_events"));assertEquals(0,count("SELECT count(*) FROM "+schema+".audit_events"));
        try(var c=source.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT jsonb_build_object('complaints',(SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM "+schema+".complaints c),'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM "+schema+".complaint_events e))::text")){r.next();assertEquals(old,r.getString(1));}
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"anchor_null","due_null","pending_has_due","source_mismatch","basis_null","occupation_room_null","event_wrong_due","event_wrong_source","event_detected_early","unknown_target_event"})
    void handlingTargetConstraintsRejectUnknownAndContradictoryDeadlineFacts(String problem) throws Exception {
        DatabaseMigrations.migrate(flyway(),false);execute("INSERT INTO "+schema+".users(username,password_hash) VALUES('owner','hash'); INSERT INTO "+schema+".complaints(user_id,operation_id,request_hash,type,status,description,submitted_at) VALUES(1,'c1111111-1111-4111-8111-111111111111','intent','service','submitted','target simulation','2026-10-29 09:00')");
        boolean event=problem.startsWith("event_")||problem.equals("unknown_target_event"),pending=problem.equals("pending_has_due")||problem.equals("unknown_target_event"),occupation=pending||problem.equals("occupation_room_null");String kind=occupation?"acceptance":"first_feedback",status=pending?"pending":"calculated",anchor=problem.equals("anchor_null")||pending?"NULL":"TIMESTAMP '2026-10-29 09:00'",due=problem.equals("due_null")||problem.equals("unknown_target_event")?"NULL":occupation?"TIMESTAMP '2026-10-29 09:15'":"TIMESTAMP '2026-10-30 09:00'",version=occupation&&!problem.equals("occupation_room_null")?"1":"NULL",basis=problem.equals("basis_null")?"NULL":pending?"'next_room_opening'":occupation?"'open_at_submission'":"'service_24h'",origin=problem.equals("source_mismatch")?"legacy_derived":"new_submission";
        String insert="INSERT INTO "+schema+".complaint_targets(complaint_id,kind,calculation_status,anchor_at,due_at,reason,room_version,calculated_at,basis,source) VALUES(1,'"+kind+"','"+status+"',"+anchor+","+due+",'safe reason',"+version+",TIMESTAMP '2026-10-29 09:00',"+basis+",'"+origin+"')";
        if(!event)assertThrows(SQLException.class,()->execute(insert));else{execute(insert);String occurred=problem.equals("event_wrong_due")?"2026-10-30 09:01":"2026-10-30 09:00",detected=problem.equals("event_detected_early")?"2026-10-30 08:59":"2026-10-30 10:00",sourceName=problem.equals("event_wrong_source")?"legacy_derived":"new_submission";assertThrows(SQLException.class,()->execute("INSERT INTO "+schema+".complaint_target_events(complaint_id,occurred_at,detected_at,source) VALUES(1,TIMESTAMP '"+occurred+"',TIMESTAMP '"+detected+"','"+sourceName+"')"));}
    }
    void manualViolationFacts() throws Exception {
        execute("INSERT INTO "+schema+".users(username,password_hash,role) VALUES('admin','hash','admin'),('reporter','hash','student'),('subject','hash','staff'),('other','hash','student'); INSERT INTO "+schema+".rooms(name,seat_rows,seat_cols) VALUES('S18 migration simulation',1,1); INSERT INTO "+schema+".seats(room_id,seat_no,row_no,col_no) VALUES(1,'01',1,1); INSERT INTO "+schema+".reservations(user_id,seat_id,res_date,start_time,end_time,status) VALUES(2,1,'2026-10-29','10:00','11:00','cancelled'); INSERT INTO "+schema+".complaints(user_id,operation_id,request_hash,type,status,description,reservation_id,room_id,submitted_at,handler_id,accepted_at) VALUES(2,'d1111111-1111-4111-8111-111111111111','old-intent','occupation','processing','migration simulation',1,1,'2026-10-29 10:01',1,'2026-10-29 10:02'); INSERT INTO "+schema+".complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,occurred_at,outcome,public_note,private_note,subject_user_id,identity_confirmed,identity_basis,facts_confirmed,valid_reservation_confirmed) VALUES(1,1,'verification','admin_self_report','2026-10-29 10:30','d2222222-2222-4222-8222-222222222222','old-event-1','2026-10-29 10:05','verified_refusal','public','private facts',3,true,'private identity',true,true),(1,1,'verification','admin_self_report','2026-10-29 10:31','d3333333-3333-4333-8333-333333333333','old-event-2','2026-10-29 10:05','verified_refusal','public','private facts',3,true,'private identity',true,true)");
    }
    @Test void verifiedViolationMigrationPreservesOldAutomaticFactsAndDoesNotGuessNotificationTime() throws Exception {
        var previous=Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema).cleanDisabled(true).baselineOnMigrate(false).target("14").locations("classpath:db/migration").load();DatabaseMigrations.migrate(previous,false);manualViolationFacts();execute("INSERT INTO "+schema+".violations(user_id,reservation_id,type,event_key,occurred_at,registered_at,status,reason,source) VALUES(2,1,'no_show','unused:1','2026-10-29 10:15','2026-10-29 10:16','active','old exact reason','automatic'); INSERT INTO "+schema+".notifications(user_id,type,title,content,created_at) VALUES(2,'violation','old violation title','plausible text must not be treated as typed evidence','2026-10-29 10:16')");DatabaseMigrations.migrate(flyway(),false);
        assertEquals(1,count("SELECT count(*) FROM "+schema+".violations WHERE type='no_show' AND event_key='unused:1' AND reason='old exact reason' AND occurred_at=TIMESTAMP '2026-10-29 10:15' AND registered_at=TIMESTAMP '2026-10-29 10:16' AND source='automatic' AND registrant_id IS NULL AND source_event_id IS NULL AND operation_id IS NULL AND request_hash IS NULL"));assertEquals(1,count("SELECT count(*) FROM "+schema+".notifications WHERE violation_id IS NULL AND title='old violation title' AND created_at=TIMESTAMP '2026-10-29 10:16'"));assertEquals(0,count("SELECT count(*) FROM "+schema+".audit_events"));assertEquals(2,count("SELECT count(*) FROM "+schema+".complaint_events WHERE outcome='verified_refusal' AND private_note='private facts'"));
    }
    String validManualViolation(String operation,int event,String occurred,String state){return "INSERT INTO "+schema+".violations(user_id,reservation_id,type,event_key,occurred_at,registered_at,status,reason,source,registrant_id,source_event_id,operation_id,request_hash) VALUES(3,1,'occupation_refusal','event:"+event+":"+operation+"',TIMESTAMP '"+occurred+"',TIMESTAMP '2026-10-29 10:40','"+state+"','public reason','manual',1,"+event+",'"+operation+"','request-hash')";}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"registrant_null","event_null","operation_null","hash_null","wrong_type","duplicate_source","duplicate_tuple","revoked_duplicate_tuple","notification_wrong_owner","notification_wrong_type","notification_with_complaint","notification_duplicate","automatic_with_manual_metadata"})
    void manualViolationConstraintsAndTypedNotificationOwnerRejectInvalidOrRepeatedFacts(String problem) throws Exception {
        DatabaseMigrations.migrate(flyway(),false);manualViolationFacts();String operation="e1111111-1111-4111-8111-111111111111",valid=validManualViolation(operation,1,"2026-10-29 10:05",problem.equals("revoked_duplicate_tuple")?"revoked":"active");
        if(problem.startsWith("notification_")||problem.startsWith("duplicate_")||problem.equals("revoked_duplicate_tuple")) {
            execute(valid);String invalid;
            if(problem.equals("duplicate_source"))invalid=validManualViolation("e2222222-2222-4222-8222-222222222222",1,"2026-10-29 10:06","active");
            else if(problem.equals("duplicate_tuple")||problem.equals("revoked_duplicate_tuple"))invalid=validManualViolation("e2222222-2222-4222-8222-222222222222",2,"2026-10-29 10:05","active");
            else {if(problem.equals("notification_duplicate"))execute("INSERT INTO "+schema+".notifications(user_id,type,title,content,violation_id) VALUES(3,'violation','typed','typed evidence',1)");invalid="INSERT INTO "+schema+".notifications(user_id,type,title,content,violation_id,complaint_id) VALUES("+(problem.equals("notification_wrong_owner")?"4":"3")+",'"+(problem.equals("notification_wrong_type")?"complaint_feedback":"violation")+"','typed','typed evidence',1,"+(problem.equals("notification_with_complaint")?"1":"NULL")+")";}
            assertThrows(SQLException.class,()->execute(invalid));
        }else{String changed=switch(problem){case "registrant_null"->valid.replace("'manual',1,1,","'manual',NULL,1,");case "event_null"->valid.replace("'manual',1,1,","'manual',1,NULL,");case "operation_null"->valid.replace("'"+operation+"','request-hash'","NULL,'request-hash'");case "hash_null"->valid.replace("'request-hash'","NULL");case "wrong_type"->valid.replace("'occupation_refusal'","'no_show'");default->valid.replace("'manual'","'automatic'");};assertThrows(SQLException.class,()->execute(changed));}
    }
    @Test void failedMigrationIsDiagnosticAndTransactional() throws Exception {
        var f=flyway(); DatabaseMigrations.migrate(f,false);
        Files.writeString(scripts.resolve("V9999__broken.sql"),"CREATE TABLE rollback_probe(id bigint); INSERT INTO missing_table VALUES(1);");
        var broken=flyway("classpath:db/migration","filesystem:"+scripts);
        var error=assertThrows(FlywayException.class,()->DatabaseMigrations.migrate(broken,false));
        assertTrue(error.getMessage().contains("V9999__broken.sql"));
        assertEquals(0,count("SELECT count(*) FROM information_schema.tables WHERE table_schema='"+schema+"' AND table_name='rollback_probe'"));
        assertEquals(15,count("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE success"));
    }
}
