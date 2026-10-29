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
        assertEquals(2,count("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".booking_mutex WHERE id=1"));
        assertEquals(0,count("SELECT count(*) FROM "+schema+".audit_events"));
        assertThrows(FlywayException.class,f::clean);
    }
    @Test void legacyRequiresExplicitAdoptionAndPreservesData() throws Exception {
        legacy(); var f=flyway();
        assertThrows(IllegalStateException.class,()->DatabaseMigrations.migrate(f,false));
        DatabaseMigrations.migrate(f,true); DatabaseMigrations.migrate(f,false);
        assertEquals(1,count("SELECT count(*) FROM "+schema+".users WHERE username='legacy' AND password_hash='hash'"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".reservations WHERE user_id=1 AND seat_id=1 AND status='pending'"));
        assertEquals(2,count("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE type='BASELINE'"));
    }
    @Test void unknownStructureRejectedWithoutBaseline() throws Exception {
        legacy(); execute("ALTER TABLE "+schema+".users ADD COLUMN unrecognized text");
        var error=assertThrows(IllegalStateException.class,()->DatabaseMigrations.migrate(flyway(),true));
        assertTrue(error.getMessage().contains("拒绝接管"));
        assertEquals(0,count("SELECT count(*) FROM information_schema.tables WHERE table_schema='"+schema+"' AND table_name='flyway_schema_history'"));
        assertEquals(0,count("SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 'baseline_check_%'"));
    }
    @Test void failedMigrationIsDiagnosticAndTransactional() throws Exception {
        var f=flyway(); DatabaseMigrations.migrate(f,false);
        Files.writeString(scripts.resolve("V3__broken.sql"),"CREATE TABLE rollback_probe(id bigint); INSERT INTO missing_table VALUES(1);");
        var broken=flyway("classpath:db/migration","filesystem:"+scripts);
        var error=assertThrows(FlywayException.class,()->DatabaseMigrations.migrate(broken,false));
        assertTrue(error.getMessage().contains("V3__broken.sql"));
        assertEquals(0,count("SELECT count(*) FROM information_schema.tables WHERE table_schema='"+schema+"' AND table_name='rollback_probe'"));
        assertEquals(2,count("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE success"));
    }
}
