package cn.studyroom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="studyroom.scheduler-enabled",havingValue="true",matchIfMissing=true)
public class ComplaintTargetScheduler {
    private final ComplaintTargetService targets;
    public ComplaintTargetScheduler(ComplaintTargetService targets){this.targets=targets;}
    @Scheduled(fixedDelay=60000,initialDelay=30000)public void settle(){targets.settle();}
}
