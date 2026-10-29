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
    public SeedData(Store db,Clock clock,@Value("${studyroom.seed:true}") boolean enabled,OpeningScheduleService opening) { this.db=db;this.clock=clock;this.enabled=enabled;this.opening=opening; }
    @Override @Transactional public void run(ApplicationArguments args) {
        if(!enabled) return;db.lockBookings();if(db.count("SELECT count(*) FROM users")>0) return;
        var bcrypt=new BCryptPasswordEncoder();String studentHash=bcrypt.encode("123456");
        db.update("INSERT INTO users(username,password_hash,real_name,role) VALUES(?,?,?,'admin')","admin",bcrypt.encode("admin123"),"管理员");
        var studentIds=new java.util.ArrayList<Long>();
        for(int i=1;i<=40;i++) studentIds.add(db.insert("INSERT INTO users(username,password_hash,real_name,campus_id) VALUES(?,?,?,?)",String.format("stu%02d",i),studentHash,"模拟学生"+i,"DEMO"+String.format("%04d",i)));
        int[][] layouts={{6,8},{8,10},{5,6}};String[] names={"1F-静音自习室","2F-综合学习区","3F-电脑研学区"};String[] zones={"quiet","regular","computer"};
        for(int r=0;r<3;r++) {
            long room=db.insert("INSERT INTO rooms(name,location,seat_rows,seat_cols,description) VALUES(?,?,?,?,?)",names[r],"示例图书馆 "+(r+1)+"楼",layouts[r][0],layouts[r][1],"课程演示用模拟空间");
            opening.initializeDefaults(room);
            for(int row=1;row<=layouts[r][0];row++) for(int col=1;col<=layouts[r][1];col++) {
                long seat=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no,zone,has_power,near_window) VALUES(?,?,?,?,?,?,?)",room,String.format("%02d-%02d",row,col),row,col,zones[r],col%2==0,col==1);
                // 明确为模拟历史，随当天生成，不复制原项目已过期的固定日期。
                if((row+col)%4==0) for(int d=1;d<=14;d++) {
                    LocalDate day=LocalDate.now(clock).minusDays(d);int hour=9+(row+col+d)%8;
                    long user=studentIds.get((row*col+d)%40);
                    db.update("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,checkin_at,checkout_at) VALUES(?,?,?,CAST(? AS TIME),CAST(? AS TIME),'completed',?,?)",
                            user,seat,Date.valueOf(day),Slot.format(hour*60),Slot.format((hour+1)*60),Timestamp.valueOf(day.atTime(hour,0)),Timestamp.valueOf(day.atTime(hour+1,0)));
                }
            }
        }
        db.update("INSERT INTO notifications(user_id,type,title,content) SELECT id,'system','欢迎使用','当前空间、用户及历史记录均为课程演示模拟数据。' FROM users");
    }
}
