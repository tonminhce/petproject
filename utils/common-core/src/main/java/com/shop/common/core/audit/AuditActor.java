package com.shop.common.core.audit;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;

import java.util.Optional;
import java.util.function.Supplier;

public final class AuditActor {

    public static final String SYSTEM = "system";

    private AuditActor() {
    }

    public static String require(Supplier<Optional<String>> auditor) {
        String actor = auditor == null ? null : auditor.get().orElse(null);
        if (actor == null || actor.isBlank()) {
            throw BusinessException.of(ErrorCode.AUDIT_ACTOR_UNAVAILABLE);
        }
        return actor;
    }

    public static String system(String job) {
        if (job == null || job.isBlank()) {
            throw new IllegalArgumentException("the job name must not be blank");
        }
        return SYSTEM + ":" + job;
    }
}
