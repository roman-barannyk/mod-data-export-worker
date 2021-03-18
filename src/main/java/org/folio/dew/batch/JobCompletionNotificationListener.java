package org.folio.dew.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.folio.des.domain.JobParameterNames;
import org.folio.des.domain.entity.Job;
import org.folio.des.service.JobUpdatesService;
import org.folio.dew.repository.IAcknowledgementRepository;
import org.folio.dew.utils.BursarFeesFinesUtils;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.listener.JobExecutionListenerSupport;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Log4j2
public class JobCompletionNotificationListener extends JobExecutionListenerSupport {

  private final IAcknowledgementRepository acknowledgementRepository;
  private final KafkaTemplate<String, Job> kafkaTemplate;

  @Override
  public void beforeJob(JobExecution jobExecution) {
    processJobUpdate(jobExecution, false);
  }

  @Override
  public void afterJob(JobExecution jobExecution) {
    processJobUpdate(jobExecution, true);
  }

  private void processJobUpdate(JobExecution jobExecution, boolean after) {
    JobParameters jobParameters = jobExecution.getJobParameters();
    String jobId = jobParameters.getString(JobParameterNames.JOB_ID);
    if (StringUtils.isBlank(jobId)) {
      log.error("Job update with empty Job ID {}.", jobExecution);
      return;
    }
    log.info("Job update {}.", jobExecution);

    if (after) {
      processJobAfter(jobId, jobParameters);
    }

    Job jobExecutionUpdate = createJobExecutionUpdate(jobId, jobExecution);

    log.info("Sending {}.", jobExecutionUpdate);
    kafkaTemplate.send(JobUpdatesService.DATA_EXPORT_JOB_EXECUTION_UPDATES_TOPIC_NAME, jobExecutionUpdate.getId().toString(),
        jobExecutionUpdate);
    log.info("Sent job {} update.", jobExecutionUpdate.getId());
    if (after) {
      log.info("-----------------------------JOB---ENDS-----------------------------");
    }
  }

  private void processJobAfter(String jobId, JobParameters jobParameters) {
    Acknowledgment acknowledgment = acknowledgementRepository.getAcknowledgement(jobId);
    if (acknowledgment != null) {
      acknowledgment.acknowledge();
      acknowledgementRepository.deleteAcknowledgement(jobId);
    }

    String tempOutputFilePath = jobParameters.getString(JobParameterNames.TEMP_OUTPUT_FILE_PATH);
    if (StringUtils.isNotBlank(tempOutputFilePath)) {
      String path = FilenameUtils.getFullPath(tempOutputFilePath);
      String fileNameStart = FilenameUtils.getName(tempOutputFilePath);
      if (StringUtils.isNotBlank(path) && StringUtils.isNotBlank(fileNameStart)) {
        File[] files = new File(path).listFiles((dir, name) -> name.startsWith(fileNameStart));
        if (files != null && files.length > 0) {
          for (File f : files) {
            f.delete();
          }
          log.info("Deleted temp files {} of job {}.", files, jobId);
        }
      }
    }
  }

  private Job createJobExecutionUpdate(String jobId, JobExecution jobExecution) {
    Job result = new Job();

    result.setId(UUID.fromString(jobId));

    ExecutionContext executionContext = jobExecution.getExecutionContext();

    if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
      result.setDescription(fetchDescription(jobExecution));
    }

    String outputFilesInStorage = executionContext.containsKey(JobParameterNames.OUTPUT_FILES_IN_STORAGE) ?
        executionContext.getString(JobParameterNames.OUTPUT_FILES_IN_STORAGE) :
        null;
    if (StringUtils.isNotBlank(outputFilesInStorage)) {
      result.setFiles(Arrays.asList(outputFilesInStorage.split(";")));
    }

    result.setStartTime(jobExecution.getStartTime());
    result.setCreatedDate(jobExecution.getCreateTime());
    result.setEndTime(jobExecution.getEndTime());
    result.setUpdatedDate(jobExecution.getLastUpdated());

    List<Throwable> errors = jobExecution.getAllFailureExceptions();
    if (CollectionUtils.isNotEmpty(errors)) {
      result.setErrorDetails(errors.stream().map(t -> getThrowableRootCause(t).getMessage()).collect(Collectors.joining("\n")));
    }

    result.setBatchStatus(jobExecution.getStatus());
    result.setExitStatus(jobExecution.getExitStatus());

    return result;
  }

  private String fetchDescription(JobExecution jobExecution) {
    Integer charges = null;
    Integer refunds = null;

    Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();
    for (StepExecution stepExecution : stepExecutions) {
      String stepName = stepExecution.getStepName();
      if (stepName.equals(BursarFeesFinesUtils.CHARGE_FEESFINES_EXPORT_STEP)) {
        charges = stepExecution.getWriteCount();
      } else if (stepName.equals(BursarFeesFinesUtils.REFUND_FEESFINES_EXPORT_STEP)) {
        refunds = stepExecution.getWriteCount();
      }
    }

    return charges != null || refunds != null ? String.format("# of charges: %s\n# of refunds: %s", charges, refunds) : null;
  }

  private Throwable getThrowableRootCause(Throwable t) {
    Throwable cause = t.getCause();
    while (cause != null && cause != t) {
      t = cause;
      cause = t.getCause();
    }
    return t;
  }

}
