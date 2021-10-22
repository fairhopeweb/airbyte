/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers;

import io.airbyte.commons.application.Application;
import io.airbyte.commons.logging.LoggingHelper;
import io.airbyte.commons.logging.MdcScope;
import io.airbyte.config.OperatorDbtInput;
import io.airbyte.config.ResourceRequirements;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DbtTransformationWorker implements Worker<OperatorDbtInput, Void>, Application {

  private static final Logger LOGGER = LoggerFactory.getLogger(DbtTransformationWorker.class);

  private final String jobId;
  private final int attempt;
  private final DbtTransformationRunner dbtTransformationRunner;
  private final ResourceRequirements resourceRequirements;

  private final AtomicBoolean cancelled;

  public DbtTransformationWorker(final String jobId,
                                 final int attempt,
                                 final ResourceRequirements resourceRequirements,
                                 final DbtTransformationRunner dbtTransformationRunner) {
    this.jobId = jobId;
    this.attempt = attempt;
    this.dbtTransformationRunner = dbtTransformationRunner;
    this.resourceRequirements = resourceRequirements;

    this.cancelled = new AtomicBoolean(false);
  }

  @Override
  public Void run(final OperatorDbtInput operatorDbtInput, final Path jobRoot) throws WorkerException {
    try (final MdcScope scopedMDCChange = new MdcScope(LoggingHelper.getExtraMDCEntries(this))) {
      final long startTime = System.currentTimeMillis();

      try (dbtTransformationRunner) {
        LOGGER.info("Running dbt transformation.");
        dbtTransformationRunner.start();
        final Path transformRoot = Files.createDirectories(jobRoot.resolve("transform"));
        if (!dbtTransformationRunner.run(
            jobId,
            attempt,
            transformRoot,
            operatorDbtInput.getDestinationConfiguration(),
            resourceRequirements,
            operatorDbtInput.getOperatorDbt())) {
          throw new WorkerException("DBT Transformation Failed.");
        }
      } catch (final Exception e) {
        throw new WorkerException("Dbt Transformation Failed.", e);
      }
      if (cancelled.get()) {
        LOGGER.info("Dbt Transformation was cancelled.");
      }

      final Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
      LOGGER.info("Dbt Transformation executed in {}.", duration.toMinutesPart());

      return null;
    } catch (final RuntimeException e) {
      throw e;
    } catch (final Exception e) {
      throw new WorkerException(getApplicationName() + " failed", e);
    }
  }

  @Override
  public void cancel() {
    try (final MdcScope scopedMDCChange = new MdcScope(LoggingHelper.getExtraMDCEntries(this))) {
      LOGGER.info("Cancelling Dbt Transformation runner...");
      try {
        cancelled.set(true);
        dbtTransformationRunner.close();
      } catch (final Exception e) {
        e.printStackTrace();
      }
    } catch (final RuntimeException e) {
      throw e;
    } catch (final Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public String getApplicationName() {
    return "normalization-worker";
  }

}
