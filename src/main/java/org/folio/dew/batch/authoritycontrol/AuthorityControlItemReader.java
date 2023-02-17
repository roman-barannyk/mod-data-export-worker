package org.folio.dew.batch.authoritycontrol;

import org.folio.dew.client.EntitiesLinksStatsClient;
import org.folio.dew.config.properties.AuthorityControlJobProperties;
import org.folio.dew.domain.dto.authority.control.AuthorityControlExportConfig;
import org.folio.dew.domain.dto.authority.control.AuthorityDataStatDto;
import org.folio.dew.domain.dto.authority.control.AuthorityDataStatDtoCollection;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.folio.dew.domain.dto.authority.control.AuthorityDataStatDto.ActionEnum.UPDATE_HEADING;

@Component
@StepScope
public class AuthorityControlItemReader extends AbstractItemCountingItemStreamItemReader<AuthorityDataStatDto> {

  private final EntitiesLinksStatsClient entitiesLinksStatsClient;
  private final int limit;
  private final OffsetDateTime fromDate;
  private OffsetDateTime toDate;
  private int currentChunkOffset;
  private List<AuthorityDataStatDto> currentChunk;

  protected AuthorityControlItemReader(EntitiesLinksStatsClient entitiesLinksStatsClient,
                                       AuthorityControlExportConfig exportConfig,
                                       AuthorityControlJobProperties jobProperties) {
    this.limit = jobProperties.getEntitiesLinksChunkSize();
    this.entitiesLinksStatsClient = entitiesLinksStatsClient;
    this.fromDate = OffsetDateTime.of(exportConfig.getFromDate(), LocalTime.MIN, ZoneOffset.UTC);
    this.toDate = OffsetDateTime.of(exportConfig.getToDate(), LocalTime.MIN, ZoneOffset.UTC);

    setSaveState(false);
    setCurrentItemCount(0);
    setExecutionContextName(getClass().getSimpleName() + '_' + UUID.randomUUID());
  }

  @Override
  protected AuthorityDataStatDto doRead() {
    if (currentChunk == null || currentChunkOffset >= currentChunk.size()) {
      if (toDate == null) {
        return null;
      }
      var collection = getItems(limit);
      currentChunk = collection.getStats();
      toDate = collection.getNext();
      currentChunkOffset = 0;
    }

    if (currentChunk.isEmpty()) {
      return null;
    }

    return currentChunk.get(currentChunkOffset++);
  }

  protected AuthorityDataStatDtoCollection getItems(int limit) {
    return entitiesLinksStatsClient.getAuthorityStats(limit, UPDATE_HEADING, fromDate.toString(), toDate.toString());
  }

  @Override
  protected void doOpen() {
    // Nothing to do
  }

  @Override
  protected void doClose() {
    // Nothing to do
  }
}