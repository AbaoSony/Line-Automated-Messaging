package com.example.line.audit;

import com.example.line.common.logging.LogEvents;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证审计入口故障仅产生指标和安全日志，不能覆盖敏感操作的主流程结果。 */
@ExtendWith(OutputCaptureExtension.class)
class AuditServiceTest {
    @Test
    void auditEnqueueFailureDoesNotEscapeAndReportsSanitizedContext(CapturedOutput output) {
        var repository = mock(AuditRepository.class);
        doThrow(new IllegalStateException("fake-private-audit-password")).when(repository).record(anyMap());
        var meters = new SimpleMeterRegistry();
        var audit = new AuditService(repository, meters);
        assertThatCode(() -> audit.record(LogEvents.INTERNAL_PUSH_INVOKED, "fictional-operator", "127.0.0.1",
                "Ufictional-audit-user", "SUCCESS", Map.of("content", Map.of("text", "虚构完整审计文本"))))
                .doesNotThrowAnyException();
        assertThat(meters.get("persist.failed").tag("operation", "audit_log").counter().count()).isEqualTo(1);
        assertThat(output).contains("persist.failed", "Ufictional-audit-user").doesNotContain("fake-private-audit-password");
    }
}
