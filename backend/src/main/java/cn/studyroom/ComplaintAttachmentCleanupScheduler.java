package cn.studyroom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="studyroom.scheduler-enabled",havingValue="true",matchIfMissing=true)
public class ComplaintAttachmentCleanupScheduler {
    private final AttachmentService attachments;
    public ComplaintAttachmentCleanupScheduler(AttachmentService attachments) { this.attachments=attachments; }
    @Scheduled(fixedDelay=600000,initialDelay=30000) public void cleanup() { attachments.cleanupExpired(); }
}
