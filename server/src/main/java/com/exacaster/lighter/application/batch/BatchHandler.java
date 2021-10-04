package com.exacaster.lighter.application.batch;

import static org.slf4j.LoggerFactory.getLogger;

import com.exacaster.lighter.application.Application;
import com.exacaster.lighter.application.ApplicationState;
import com.exacaster.lighter.application.ApplicationStatusHandler;
import com.exacaster.lighter.backend.Backend;
import com.exacaster.lighter.configuration.AppConfiguration;
import com.exacaster.lighter.spark.SparkApp;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import javax.transaction.Transactional;
import java.util.function.Consumer;
import org.slf4j.Logger;

@Singleton
public class BatchHandler {

    private static final Logger LOG = getLogger(BatchHandler.class);

    private final Backend backend;
    private final BatchService batchService;
    private final AppConfiguration appConfiguration;
    private final ApplicationStatusHandler statusTracker;

    public BatchHandler(Backend backend, BatchService batchService, AppConfiguration appConfiguration,
            ApplicationStatusHandler statusTracker) {
        this.backend = backend;
        this.batchService = batchService;
        this.appConfiguration = appConfiguration;
        this.statusTracker = statusTracker;
    }

    public void launch(Application application, Consumer<Throwable> errorHandler) {
        var app = new SparkApp(application.getSubmitParams(), errorHandler);
        app.launch(backend.getSubmitConfiguration(application));
    }

    @Scheduled(fixedRate = "1m")
    @Transactional
    public void processScheduledBatches() {
        var emptySlots = countEmptySlots();
        LOG.info("Processing scheduled batches, found empty slots: {}", emptySlots);
        batchService.fetchByState(ApplicationState.NOT_STARTED, emptySlots)
                .forEach(batch -> {
                    LOG.info("Launching {}", batch);
                    statusTracker.processApplicationStarting(batch);
                    launch(batch, error -> statusTracker.processApplicationError(batch, error));
                });
    }

    private Integer countEmptySlots() {
        return Math.max(this.appConfiguration.getMaxRunningJobs() - this.batchService.fetchRunning().size(), 0);
    }

    @Scheduled(fixedRate = "2m")
    @Transactional
    public void trackRunning() {
        batchService.fetchRunning().forEach(statusTracker::processApplicationRunning);
    }

}
