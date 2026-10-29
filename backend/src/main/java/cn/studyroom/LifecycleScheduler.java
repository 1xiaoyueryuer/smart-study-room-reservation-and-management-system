package cn.studyroom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="studyroom.scheduler-enabled",havingValue="true",matchIfMissing=true)
public class LifecycleScheduler {
    private final BookingService bookings;
    public LifecycleScheduler(BookingService bookings) { this.bookings=bookings; }
    @Scheduled(fixedDelay=10000,initialDelay=15000) public void tick() { bookings.tick(); }
}
