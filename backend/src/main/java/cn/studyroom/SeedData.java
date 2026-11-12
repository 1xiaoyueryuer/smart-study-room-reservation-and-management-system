package cn.studyroom;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SeedData implements ApplicationRunner {
    private final Store db;
    private final Clock clock;
    private final boolean enabled;
    private final OpeningScheduleService opening;
    private final UsageService usage;
    public SeedData(Store db,Clock clock,@Value("${studyroom.seed:true}") boolean enabled,OpeningScheduleService opening,UsageService usage) { this.db=db;this.clock=clock;this.enabled=enabled;this.opening=opening;this.usage=usage; }
    @Override @Transactional public void run(ApplicationArguments args) {
        if(!enabled) return;db.lockBookings();if(db.count("SELECT count(*) FROM users")>0) return;
        var bcrypt=new BCryptPasswordEncoder();String studentHash=bcrypt.encode("123456");
        db.update("INSERT INTO users(username,password_hash,real_name,role,campus_id) VALUES(?,?,?,'admin',?)","202630001",bcrypt.encode("admin123"),"管理员","202630001");
        var studentIds=new java.util.ArrayList<Long>();
        // Stable teaching-demo identities match the documented login examples across fresh installs.
        int admissionYear=2026;
        for(int i=1;i<=40;i++) {
            String identity=String.format("%04d%1d%04d",admissionYear,0,i);
            studentIds.add(db.insert("INSERT INTO users(username,password_hash,real_name,campus_id) VALUES(?,?,?,?)",identity,studentHash,"模拟学生"+i,identity));
        }
        int[][] layouts={{6,8},{8,10},{5,6}};String[] names={"1F-静音自习室","2F-综合学习区","3F-电脑研学区"};String[] zones={"quiet","regular","computer"};
        for(int r=0;r<3;r++) {
            long room=db.insert("INSERT INTO rooms(name,location,seat_rows,seat_cols,description) VALUES(?,?,?,?,?)",names[r],"示例图书馆 "+(r+1)+"楼",layouts[r][0],layouts[r][1],"课程演示用模拟空间");
            opening.initializeDefaults(room);
            for(int row=1;row<=layouts[r][0];row++) for(int col=1;col<=layouts[r][1];col++) {
                long seat=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no,zone,has_power,near_window) VALUES(?,?,?,?,?,?,?)",room,String.format("%02d-%02d",row,col),row,col,zones[r],col%2==0,col==1);
                // 明确为模拟历史，随当天生成，不复制早期固定的过期日期。
                if((row+col)%4==0) for(int d=1;d<=14;d++) {
                    LocalDate day=LocalDate.now(clock).minusDays(d);int hour=9+(row+col+d)%8;
                    long user=studentIds.get((row*col+d)%40);
                    long reservation=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,ended_at,termination_reason) VALUES(?,?,?,CAST(? AS TIME),CAST(? AS TIME),'completed',?,'demo_completed')",
                            user,seat,Date.valueOf(day),Slot.format(hour*60),Slot.format((hour+1)*60),Timestamp.valueOf(day.atTime(hour+1,0)));
                    usage.start(reservation,day.atTime(hour,0),"demo_seed");usage.finish(reservation,day.atTime(hour+1,0),day.atTime(hour+1,0),"demo_completed",LocalDateTime.now(clock));
                }
            }
        }
        db.update("INSERT INTO notifications(user_id,type,title,content) SELECT id,'system','欢迎使用','当前空间、用户及历史记录均为课程演示模拟数据。' FROM users");
    }
}
