/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.datacollector.execution.runner.common;

import com.streamsets.datacollector.config.PipelineConfiguration;
import com.streamsets.datacollector.execution.PipelineStatus;
import com.streamsets.datacollector.execution.StateListener;
import com.streamsets.datacollector.main.RuntimeInfo;
import com.streamsets.datacollector.metrics.MetricsConfigurator;
import com.streamsets.datacollector.runner.Pipeline;
import com.streamsets.datacollector.runner.PipelineRuntimeException;
import com.streamsets.datacollector.runner.production.ProductionSourceOffsetTracker;
import com.streamsets.datacollector.util.ContainerError;
import com.streamsets.datacollector.validation.Issue;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.impl.ErrorMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class ProductionPipeline {

  private static final Logger LOG = LoggerFactory.getLogger(ProductionPipeline.class);

  private final RuntimeInfo runtimeInfo;
  private final PipelineConfiguration pipelineConf;
  private final Pipeline pipeline;
  private final ProductionPipelineRunner pipelineRunner;
  private StateListener stateListener;
  private volatile PipelineStatus pipelineStatus;

  public ProductionPipeline(RuntimeInfo runtimeInfo, PipelineConfiguration pipelineConf, Pipeline pipeline) {
    this.runtimeInfo = runtimeInfo;
    this.pipelineConf = pipelineConf;
    this.pipeline = pipeline;
    this.pipelineRunner =  (ProductionPipelineRunner)pipeline.getRunner();
  }

  public StateListener getStatusListener() {
    return this.stateListener;
  }

  public void registerStatusListener(StateListener stateListener) {
    this.stateListener = stateListener;
  }

  private void stateChanged(PipelineStatus pipelineStatus, String message, Map<String, Object> attributes)
    throws PipelineRuntimeException {
    this.pipelineStatus = pipelineStatus;
    if (stateListener != null) {
      stateListener.stateChanged(pipelineStatus, message, attributes);
    }
  }

  public void run() throws StageException, PipelineRuntimeException {
    MetricsConfigurator.registerJmxMetrics(runtimeInfo.getMetrics());
    boolean finishing = false;
    boolean errorWhileRunning = false;
    try {
      try {
        LOG.debug("Initializing");
        List<Issue> issues = null;
        try {
          issues = pipeline.init();
        } catch (Exception e) {
          if (!wasStopped()) {
            LOG.warn("Error while starting: {}", e.getMessage(), e);
            stateChanged(PipelineStatus.START_ERROR, e.getMessage(), null);
          }
          throw new PipelineRuntimeException(ContainerError.CONTAINER_0702, e.getMessage(), e);
        }
        if (issues.isEmpty()) {
          try {
            stateChanged(PipelineStatus.RUNNING, null, null);
            LOG.debug("Running");
            pipeline.run();
            if (!wasStopped()) {
              LOG.debug("Finishing");
              stateChanged(PipelineStatus.FINISHING, null, null);
              finishing = true;
            }
          } catch (Exception e) {
            if (!wasStopped()) {
              LOG.warn("Error while running: {}", e.getMessage(), e);
              stateChanged(PipelineStatus.RUNNING_ERROR, e.getMessage(), null);
              errorWhileRunning = true;
            }
            throw e;
          }
        } else {
          LOG.debug("Stopped due to validation error");
          PipelineRuntimeException e = new PipelineRuntimeException(ContainerError.CONTAINER_0800,
            issues.get(0).getMessage());
          stateChanged(PipelineStatus.START_ERROR, issues.get(0).getMessage(), null);
          throw e;
        }
      } finally {
        LOG.debug("Destroying");
        pipeline.destroy();
        if (finishing) {
          LOG.debug("Finished");
          stateChanged(PipelineStatus.FINISHED, null, null);
        } else if (errorWhileRunning) {
          LOG.debug("Stopped due to an error");
          stateChanged(PipelineStatus.RUN_ERROR, null, null);
        }
      }
    } finally {
      MetricsConfigurator.cleanUpJmxMetrics();
    }
  }

  public PipelineConfiguration getPipelineConf() {
    return pipelineConf;
  }

  public Pipeline getPipeline() {
    return this.pipeline;
  }

  public void stop() {
    pipelineRunner.stop();
  }

  public boolean wasStopped() {
    return pipelineRunner.wasStopped();
  }

  public String getCommittedOffset() {
    return pipelineRunner.getCommittedOffset();
  }

  public void captureSnapshot(String snapshotName, int batchSize, int batches) {
    pipelineRunner.capture(snapshotName, batchSize, batches);
  }

  public void setOffset(String offset) {
    ProductionSourceOffsetTracker offsetTracker = (ProductionSourceOffsetTracker) pipelineRunner.getOffSetTracker();
    offsetTracker.setOffset(offset);
    offsetTracker.commitOffset();
  }

  public List<Record> getErrorRecords(String instanceName, int size) {
    return pipelineRunner.getErrorRecords(instanceName, size);
  }

  public List<ErrorMessage> getErrorMessages(String instanceName, int size) {
    return pipelineRunner.getErrorMessages(instanceName, size);
  }

  public long getLastBatchTime() {
    return pipelineRunner.getOffSetTracker().getLastBatchTime();
  }

  public void setThreadHealthReporter(ThreadHealthReporter threadHealthReporter) {
    pipelineRunner.setThreadHealthReporter(threadHealthReporter);
  }
}