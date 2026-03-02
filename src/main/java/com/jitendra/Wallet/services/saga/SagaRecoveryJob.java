package com.jitendra.Wallet.services.saga;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.jitendra.Wallet.entity.SagaInstance;
import com.jitendra.Wallet.entity.SagaStatus;
import com.jitendra.Wallet.repository.SagaInstanceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled job that detects and recovers stalled saga instances.
 * A saga is considered "stalled" if it has been in STARTED or COMPENSATING
 * status for longer than the configured threshold (default: 10 minutes).
 *
 * Runs every 60 seconds by default.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SagaRecoveryJob {

    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaOrchestrator sagaOrchestrator;

    private static final int STALE_THRESHOLD_MINUTES = 10;

    @Scheduled(fixedDelay = 60000) // every 60 seconds
    public void recoverStalledSagas() {
        log.debug("Running saga recovery job...");

        Instant cutoff = Instant.now().minus(STALE_THRESHOLD_MINUTES, ChronoUnit.MINUTES);

        List<SagaInstance> stalledSagas = sagaInstanceRepository.findStalledSagas(cutoff);

        if (stalledSagas.isEmpty()) {
            log.debug("No stalled sagas found.");
            return;
        }

        log.warn("Found {} stalled saga(s), attempting recovery...", stalledSagas.size());

        for (SagaInstance saga : stalledSagas) {
            try {
                log.info("Recovering stalled saga id: {} (status: {}, updatedDate: {})",
                        saga.getId(), saga.getStatus(), saga.getUpdatedDate());

                if (saga.getStatus() == SagaStatus.STARTED) {
                    // Saga never got past STARTED — compensate to clean up
                    sagaOrchestrator.compensateSaga(saga.getId());
                } else if (saga.getStatus() == SagaStatus.COMPENSATING) {
                    // Saga was mid-compensation — retry compensation
                    sagaOrchestrator.compensateSaga(saga.getId());
                } else {
                    log.warn("Saga id: {} in unexpected status {} during recovery, marking as FAILED",
                            saga.getId(), saga.getStatus());
                    sagaOrchestrator.failSaga(saga.getId());
                }
            } catch (Exception e) {
                log.error("Failed to recover stalled saga id: {}: {}", saga.getId(), e.getMessage());
                try {
                    sagaOrchestrator.failSaga(saga.getId());
                } catch (Exception ex) {
                    log.error("Failed to mark saga {} as FAILED: {}", saga.getId(), ex.getMessage());
                }
            }
        }
    }
}
