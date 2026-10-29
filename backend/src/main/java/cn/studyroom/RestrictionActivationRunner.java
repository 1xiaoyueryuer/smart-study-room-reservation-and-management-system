package cn.studyroom;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 完成迁移后以同一实际执行核心落实仍有效余期；失败使启动失败，不假称历史已执行。 */
@Component
public class RestrictionActivationRunner implements ApplicationRunner {
    private final RestrictionWorkflow workflow;
    public RestrictionActivationRunner(RestrictionWorkflow workflow){this.workflow=workflow;}
    @Override public void run(ApplicationArguments args){workflow.settle();}
}
