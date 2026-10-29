package cn.studyroom;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@Configuration
public class DatabaseMigrations {
    // 显式配置以避免 Boot 2 的旧 Flyway 属性绑定调用已移除的 API。
    @Bean Flyway flyway(DataSource source, @Value("${studyroom.migration.adopt-legacy:false}") boolean adoptLegacy) {
        Flyway flyway = Flyway.configure().dataSource(source).defaultSchema("public")
            .locations("classpath:db/migration").baselineVersion("1")
            .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).load();
        migrate(flyway, adoptLegacy);
        return flyway;
    }

    static void migrate(Flyway flyway, boolean adoptLegacy) {
        // 仅显式接管且结构完全吻合的旧库；不启用 baselineOnMigrate。
        try (Connection c = flyway.getConfiguration().getDataSource().getConnection()) {
            String schema = flyway.getConfiguration().getDefaultSchema();
            if (schema == null) schema = c.getSchema();
            try (PreparedStatement q = c.prepareStatement("SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name='flyway_schema_history'")) {
                q.setString(1, schema);
                try (ResultSet rows = q.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) == 0 && !signature(c, schema).isEmpty()) {
                        if (!adoptLegacy) throw new IllegalStateException("旧库尚未接管：备份并核对后显式设置 studyroom.migration.adopt-legacy=true；禁止盲目 baseline");
                        verifyLegacy(c, schema);
                        flyway.baseline();
                    }
                }
            }
        } catch (SQLException e) { throw new IllegalStateException("迁移基线检查失败", e); }
        flyway.migrate();
    }

    private static void verifyLegacy(Connection c, String schema) throws SQLException {
        List<String> actual = signature(c, schema);
        String reference = "baseline_check_" + UUID.randomUUID().toString().replace("-", "");
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + reference);
            s.execute("SET LOCAL search_path TO " + reference);
            ScriptUtils.executeSqlScript(c, new org.springframework.core.io.support.EncodedResource(new ClassPathResource("db/migration/V1__postgresql_baseline.sql"), java.nio.charset.StandardCharsets.UTF_8));
            List<String> expected = signature(c, reference);
            if (!actual.equals(expected)) {
                Set<String> missing = new TreeSet<>(expected); missing.removeAll(actual);
                Set<String> extra = new TreeSet<>(actual); extra.removeAll(expected);
                throw new IllegalStateException("旧库结构与 V1 不符，拒绝接管；缺少/变化=" + missing + "; 多出/变化=" + extra);
            }
        } finally {
            // 参考结构仅用于核对，始终回滚，绝不修改旧业务数据。
            c.rollback(); c.setAutoCommit(true);
        }
    }

    private static List<String> signature(Connection c, String schema) throws SQLException {
        String sql = """
            SELECT 'relation|' || c.relname || '|' || c.relkind::text AS item
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname=? AND c.relkind IN ('r','p','v','m','S','f')
            UNION ALL
            SELECT 'column|' || c.relname || '|' || a.attnum || '|' || a.attname || '|' ||
                   format_type(a.atttypid,a.atttypmod) || '|' || a.attnotnull || '|' ||
                   coalesce(pg_get_expr(d.adbin,d.adrelid),'')
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
            LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
            WHERE n.nspname=? AND c.relkind IN ('r','p','v','m','f')
            UNION ALL
            SELECT 'constraint|' || c.relname || '|' || con.conname || '|' || pg_get_constraintdef(con.oid)
            FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=?
            UNION ALL
            SELECT 'index|' || indexname || '|' || indexdef FROM pg_indexes WHERE schemaname=?
            UNION ALL
            SELECT 'trigger|' || c.relname || '|' || t.tgname
            FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND NOT t.tgisinternal
            """;
        List<String> result = new ArrayList<>();
        try (PreparedStatement q = c.prepareStatement(sql)) {
            for (int i=1; i<=5; i++) q.setString(i, schema);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) result.add(r.getString(1).replace(schema + ".", ""));
            }
        }
        Collections.sort(result);
        return result;
    }
}
