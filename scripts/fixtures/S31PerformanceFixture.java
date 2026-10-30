import cn.studyroom.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
/** Reproducible labelled foundations, only studyroom_java_test, no benchmark run.
 * From repository root, use the built backend runtime classpath (target/classes
 * plus its resolved runtime dependency JARs) to compile this source into work/.
 * Launch with cwd=backend so existing ../.local configuration and ../work output
 * resolve consistently: java -cp <work classes + backend runtime> S31PerformanceFixture.
 * Then run scripts/teaching_performance.py from repository root against Java8081.
 * 1000 seats are split30x20+20x20, within each room's30x30 limit. Default seven
 * weekdays08:00-22:00 must contain all twenty08:00-18:00 creation intervals.
 */
class S31PerformanceFixture {
 public static void main(String[] args)throws Exception{
  TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
  try(var app=new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run("--spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/studyroom_java_test","--studyroom.seed=false","--studyroom.scheduler-enabled=false")){
   var db=app.getBean(Store.class);if(!"studyroom_java_test".equals(db.one("SELECT current_database() AS name").get("name")))throw new IllegalStateException("dedicated test database only");var result=new LinkedHashMap<String,Object>();
   var day=java.time.LocalDate.now(BookingPolicy.CAMPUS_ZONE).plusDays(1);result.put("java_runtime_version",System.getProperty("java.runtime.version"));result.put("fixture_created_at",java.time.Instant.now().toString());result.put("database_name","studyroom_java_test");
   app.getBean(TransactionTemplate.class).executeWithoutResult(tx->{
    db.lockBookings();if(db.count("SELECT count(*) FROM users WHERE username='s31_perf_student'")>0)throw new IllegalStateException("refuse overwrite");
    long user=db.insert("INSERT INTO users(username,password_hash,role,campus_id,real_name) VALUES('s31_perf_student',?,'student','S31-PERF-SIMULATED','S31工程性能模拟用户')",new BCryptPasswordEncoder().encode("S02-test-only-password"));
    var rooms=new ArrayList<Long>();var seats=new ArrayList<Long>();var opening=app.getBean(OpeningScheduleService.class);
    for(int roomIndex=1;roomIndex<=2;roomIndex++){
     int rows=roomIndex==1?30:20,cols=20;long room=db.insert("INSERT INTO rooms(name,building,floor,location,seat_rows,seat_cols) VALUES(?,'工程性能模拟楼','1','非校园现场',?,?)","S31千座模拟空间"+roomIndex,rows,cols);rooms.add(room);opening.initializeDefaults(room);
     for(int row=1;row<=rows;row++)for(int col=1;col<=cols;col++)seats.add(db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,?,?,?)",room,"P"+roomIndex+"-"+row+"-"+col,row,col));
     if(db.count("SELECT count(*) FROM opening_schedules o JOIN opening_windows w ON w.schedule_id=o.id WHERE o.room_id=? AND o.weekday BETWEEN 1 AND 7 AND o.schedule_date IS NULL AND w.start_minute=480 AND w.end_minute=1320",room)!=7)throw new IllegalStateException("new-room defaults must cover all seven weekdays at 08:00-22:00");
     var calendar=opening.calendar(room);if(!calendar.day(day,null).contains(day,day.atTime(8,0),day.atTime(18,0)))throw new IllegalStateException("fixture day must cover every planned 08:00-18:00 benchmark interval");
    }
    if(seats.size()!=1000)throw new IllegalStateException("exact 1000 simulated seats");result.put("label","Engineering single-machine teaching benchmark with 1000 simulated seats; no campus concurrency capacity claim");result.put("user",user);result.put("rooms",rooms);result.put("seats",seats);result.put("date",day.toString());result.put("default_opening_verified",true);result.put("room_layouts",List.of("30x20","20x20"));result.put("planned_interval","08:00-18:00");
    // Separate foundations keep smoke/GUI history outside the 1000-seat sample.
    var smokeUsers=new LinkedHashMap<String,Long>();
    for(String role:List.of("admin","owner","other","staff")){
     String username="s31_smoke_"+role;
     if(db.count("SELECT count(*) FROM users WHERE username=?",username)>0)throw new IllegalStateException("refuse overwrite smoke user");
     String accountRole=role.equals("admin")?"admin":role.equals("staff")?"staff":"student";
     long id=db.insert("INSERT INTO users(username,password_hash,role,campus_id,real_name) VALUES(?,?,?,?,?)",username,new BCryptPasswordEncoder().encode("S02-test-only-password"),accountRole,"S31-SMOKE-"+role,"S31工程模拟"+role);smokeUsers.put(role,id);
    }
    long smokeRoom=db.insert("INSERT INTO rooms(name,building,floor,location,seat_rows,seat_cols) VALUES('S31工程联调与页面空间','工程模拟楼','1','非校园现场',2,3)");opening.initializeDefaults(smokeRoom);
    for(int row=1;row<=2;row++)for(int col=1;col<=3;col++)db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,?,?,?)",smokeRoom,"S31-S"+row+"-"+col,row,col);
    result.put("smoke_users",smokeUsers);result.put("smoke_room",smokeRoom);
   });Files.writeString(Path.of("../work/s31-performance-fixture.json"),app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writerWithDefaultPrettyPrinter().writeValueAsString(result));System.out.println("S31 performance fixture prepared: 1000 simulated seats in 30x20 + 20x20 rooms");
  }
 }
}
