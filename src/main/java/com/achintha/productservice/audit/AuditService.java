package com.achintha.productservice.audit;

import com.achintha.productservice.security.Actor;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes audit rows for privileged mutations (admin, super admin, merchant owner, assistant, system), in the
 * caller's transaction so the row exists exactly when the change does. Callers pass snapshots with sensitive fields
 * already masked (this service holds no secrets or PII beyond user ids).
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    public static final String TARGET_CATEGORY = "CATEGORY";
    public static final String TARGET_PRODUCT = "PRODUCT";
    public static final String TARGET_VARIANT = "VARIANT";
    public static final String TARGET_DISCOUNT = "DISCOUNT";
    public static final String TARGET_REVIEW = "REVIEW";

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, String action, String targetType, String targetId,
                       Map<String, ?> before, Map<String, ?> after, String reason) {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setOccurredAt(clock.instant());
        entry.setActorId(actor.id());
        entry.setActorPublicId(actor.publicId() != null ? actor.publicId() : Actor.SYSTEM);
        entry.setActorRole(Actor.SYSTEM.equals(actor.publicId()) ? Actor.SYSTEM : actor.role().name());
        entry.setActorStoreId(actor.storeId());
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setBeforeState(before == null ? null : objectMapper.writeValueAsString(before));
        entry.setAfterState(after == null ? null : objectMapper.writeValueAsString(after));
        entry.setReason(reason);
        repository.save(entry);
    }
}
