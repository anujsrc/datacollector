/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.cluster;

import com.google.common.base.Throwables;
import com.streamsets.pipeline.OffsetAndResult;
import com.streamsets.pipeline.api.impl.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Implements the consumer side of the cluster producer consumer pattern.
 * Ensures that each take is followed by a subsequent commit and stops
 * attempting to consume messages when the producer indicates it has
 * completed.
 */
public class Consumer {
  private static final Logger LOG = LoggerFactory.getLogger(Consumer.class);
  private final ControlChannel controlChannel;
  private final DataChannel dataChannel;
  private boolean running;
  private boolean batchCommitted;
  private volatile Throwable producerError;
  private volatile Throwable consumerError;
  private String lastCommittedOffset;

  public Consumer(ControlChannel controlChannel, DataChannel dataChannel) {
    this.controlChannel = controlChannel;
    this.dataChannel = dataChannel;
    this.running = true;
    this.batchCommitted = true;
    this.lastCommittedOffset = "";
  }

  /**
   * Consumes messages off the queue. Returns null when the producer
   * has indicated it is complete and throws an exception
   * when the consumer producer has indicated it is in error.
   */
  public OffsetAndResult<Map.Entry> take() {
    if (producerError != null) {
      throw new RuntimeException(Utils.format("Producer encountered error: {}", producerError), producerError);
    }
    try {
      Utils.checkState(batchCommitted, "Cannot take messages when last batch is uncommitted");
      while (running) {
        for (ControlChannel.Message controlMessage : controlChannel.getConsumerMessages()) {
          switch (controlMessage.getType()) {
            case PRODUCER_COMPLETE:
              // producer is complete, empty channel and afterwards return null
              running = false;
              break;
            case PRODUCER_ERROR:
              running = false;
              Throwable throwable = (Throwable) controlMessage.getPayload();
              producerError = throwable;
              throw new ProducerRuntimeException(Utils.format("Producer encountered error: {}", throwable), throwable);
            default:
              String msg = Utils.format("Illegal control message type: '{}'", controlMessage.getType());
              throw new IllegalStateException(msg);
          }
        }
        OffsetAndResult<Map.Entry> batch = dataChannel.take(10, TimeUnit.MILLISECONDS);
        LOG.trace("Received batch: {}", batch);
        if (batch != null) {
          batchCommitted = false; // got a new batch
          return batch;
        }
      }
      LOG.trace("Returning null");
      return null;
    } catch (Throwable throwable) {
      if (!(throwable instanceof ProducerRuntimeException)) {
        controlChannel.consumerError(throwable);
        if (consumerError != null) {
          consumerError = throwable;
        }
      }
      throw Throwables.propagate(throwable);
    }
  }

  /**
   * Commit the offset. Required after take has returned a non-null value.
   */
  public void commit(String offset) {
    batchCommitted = true;
    LOG.trace("Last committed offset '{}', attempting to commit '{}'", lastCommittedOffset, offset);
    Utils.checkState(null != lastCommittedOffset, "Last committed offset cannot be null");
    controlChannel.consumerCommit(offset);
    lastCommittedOffset = offset;
  }

  /**
   * Send a control message indicating the consumer has encountered an error.
   */
  public void error(Throwable throwable) {
    if (consumerError != null) {
      consumerError = throwable;
    }
    controlChannel.consumerError(throwable);
  }

  public boolean inErrorState() {
    return consumerError != null || producerError != null;
  }
}