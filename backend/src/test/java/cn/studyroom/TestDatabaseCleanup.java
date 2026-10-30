package cn.studyroom;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.AopTestUtils;

/** Keep deferred batch cardinality and all foreign keys enabled during test-only cleanup. */
final class TestDatabaseCleanup {
    private TestDatabaseCleanup() {}
    static void clear(Store db) {
        Store target=AopTestUtils.getUltimateTargetObject(db);
        var transactions=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(target.jdbc.getDataSource())));
        transactions.executeWithoutResult(status->{
            if(!"studyroom_java_test".equals(db.one("SELECT current_database() AS name").get("name")))throw new IllegalStateException("test database only");
            db.lockBookings();
            db.update("DELETE FROM notifications WHERE appeal_id IS NOT NULL");
            for(String table:List.of("evidence_attachments","restriction_recalculations","appeal_reviews","appeal_events","appeals","notifications","restriction_operations","restriction_evaluation_sources","restriction_evaluations","restrictions","violations","complaint_target_events","complaint_targets","reminders","complaint_events","complaints","resource_change_operations","usage_records","seat_codes","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","waitlist","reservations","seats","rooms","users"))db.update("DELETE FROM "+table);
        });
    }
}
