package com.example.line.ai.coordination;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 虚构凭证只用于内存断言；准入、版本核对、单次发送与引用释放必须在同一协调器中。 */
class TurnCredentialTest extends TurnTestSupport {
    @Test void explicitSilentOutcomeCompletesOnlyAfterActualWorkCleanupAndCannotAcquireSendPermission() {
        var admission=new java.util.concurrent.atomic.AtomicReference<TurnAdmission>();
        setup((ticket,snapshot,deadline) -> {
            if(ticket.kind()!=TurnTicket.Kind.GENERATION) coordinator.completeDecision(ticket,now());
            else {
                assertThat(coordinator.finishUnsent(ticket,TurnResult.Reason.TECHNICAL_SILENT)).isTrue();
                assertThat(coordinator.claimSending(ticket)).isNull();
                assertThat(admission.get().completion().toCompletableFuture().isDone()).isFalse();
            }
        },"mode","RULE");
        admission.set(coordinator.admit(member("Ufictional",1),deadline(),"fictional-silent-credential"));
        executor.drain();assertThat(result(admission.get())).isEqualTo(new TurnResult(TurnState.FAILED,TurnResult.Reason.TECHNICAL_SILENT));
        assertThat(coordinator.isIdle()).isTrue();
    }
    @Test void latestAdmittedCredentialWinsWhileDuplicateRejectedAndBlankCannotReplaceIt() {
        setup((ticket,snapshot,deadline) -> {
            if(ticket.kind()!=TurnTicket.Kind.GENERATION) coordinator.completeDecision(ticket,now());
            else {
                var total=coordinator.totalDeadline(ticket);
                try(var permit=coordinator.claimSending(ticket)) {
                    assertThat(permit.token()).isEqualTo("fictional-latest-credential");
                    assertThat(permit.toString()).doesNotContain(permit.token());
                    assertThat(permit.deadline().expiresNanos()).isEqualTo(total.expiresNanos());
                    assertThat(coordinator.claimSending(ticket)).isNull();
                    coordinator.finishSending(ticket,TurnState.ACCEPTED);
                    permit.close();assertThat(permit.token()).isNull();
                }
            }
        },"mode","RULE");
        var first=coordinator.admit(member("Ufictional",1),deadline(),"fictional-first-credential");
        coordinator.admit(member("Ufictional",2),deadline(),"fictional-latest-credential");
        assertThat(coordinator.admit(member("Ufictional",2),deadline(),"fictional-duplicate-credential").duplicate()).isTrue();
        assertThat(coordinator.admit(member("Ufictional",1),deadline(),"fictional-old-credential").duplicate()).isTrue();
        coordinator.admit(member("Ufictional",3),deadline()," ");
        var oversized=member("Ufictional",4);
        oversized=new com.example.line.ai.aggregation.InboundMember(oversized.userId(),oversized.eventId(),oversized.messageId(),
                "虛".repeat(4097),oversized.occurredAt(),oversized.receivedAt(),oversized.sequence(),oversized.traceId());
        assertThat(coordinator.admit(oversized,deadline(),"fictional-rejected-credential").admitted()).isFalse();
        executor.drain();assertThat(result(first).state()).isEqualTo(TurnState.ACCEPTED);assertThat(coordinator.isIdle()).isTrue();
    }
    @Test void cancelledVersionCannotClaimItsCredentialOrReadAnotherEpochDeadline() {
        var tickets=new ArrayList<TurnTicket>();
        setup((ticket,snapshot,deadline) -> {
            tickets.add(ticket); coordinator.cancel(ticket.userId());
            assertThat(coordinator.claimSending(ticket)).isNull();assertThat(coordinator.totalDeadline(ticket)).isNull();
        },"mode","RULE");
        var first=coordinator.admit(member("Ufictional",1),deadline(),"fictional-cancelled-credential");
        executor.drain();assertThat(result(first).state()).isEqualTo(TurnState.CANCELLED);
        coordinator.admit(member("Ufictional",2),deadline(),"fictional-new-credential");
        assertThat(coordinator.claimSending(tickets.get(0))).isNull();executor.drain();assertThat(coordinator.isIdle()).isTrue();
    }
}
