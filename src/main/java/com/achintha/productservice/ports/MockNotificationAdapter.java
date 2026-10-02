package com.achintha.productservice.ports;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Logs instead of sending; always succeeds. */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ports.notification", havingValue = "mock", matchIfMissing = true)
public class MockNotificationAdapter implements NotificationPort {

    @Override
    public void notifyStore(UUID storeId, String template, String subject) {
        log.info("[mock notification] to=store:{} template={} subject={}", storeId, template, subject);
    }

    @Override
    public void notifyAdmins(String template, String subject) {
        log.info("[mock notification] to=admins template={} subject={}", template, subject);
    }
}
