package cn.studyroom;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL 与行映射的唯一入口；数据库时间返回 ISO 字符串，保持原 Vue 接口格式。 */
@Repository
public class Store {
    final JdbcTemplate jdbc;
    public Store(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Map<String,Object>> list(String sql,Object...args) { return jdbc.query(sql,Store::row,args); }
    public Map<String,Object> one(String sql,Object...args) {
        var rows=list(sql,args); Api.require(!rows.isEmpty(),404,"记录不存在"); return rows.get(0);
    }
    public int update(String sql,Object...args) { return jdbc.update(sql,args); }
    public long count(String sql,Object...args) { return jdbc.queryForObject(sql,Long.class,args); }
    public long insert(String sql,Object...args) { return jdbc.queryForObject(sql+" RETURNING id",Long.class,args); }
    public void lockBookings() { jdbc.queryForObject("SELECT id FROM booking_mutex WHERE id=1 FOR UPDATE",Integer.class); }
    public static long id(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public static String text(Map<String,Object> row,String key) { return Objects.toString(row.get(key),""); }
    private static Map<String,Object> row(ResultSet rs,int n) throws SQLException {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {
            String label=rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT);
            // PostgreSQL TIME允许24:00:00，getObject(java.sql.Time)会丢失此边界。
            if(rs.getMetaData().getColumnType(i)==java.sql.Types.TIME) { result.put(label,rs.getString(i));continue; }
            Object value=rs.getObject(i);
            // 日期与业务时间戳分别映射，保持前端 YYYY-MM-DD 契约。
            if(label.equals("res_date")&&value!=null) value=rs.getObject(i,java.time.LocalDate.class).toString();
            else if(value instanceof Timestamp) {
                // JDBC legacy Date/Timestamp silently apply the mixed Julian/Gregorian calendar.
                // Business timestamps are local campus time; timestamptz retains its actual offset.
                boolean zoned=rs.getMetaData().getColumnType(i)==java.sql.Types.TIMESTAMP_WITH_TIMEZONE||"timestamptz".equals(rs.getMetaData().getColumnTypeName(i));
                value=zoned?rs.getObject(i,java.time.OffsetDateTime.class).toString():rs.getObject(i,java.time.LocalDateTime.class).toString();
            }
            else if(value instanceof java.sql.Date) value=rs.getObject(i,java.time.LocalDate.class).toString();
            result.put(label,value);
        }
        return result;
    }
}
