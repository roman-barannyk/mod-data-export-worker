package org.folio.dew.controller;

import lombok.extern.log4j.Log4j2;
import org.folio.des.config.KafkaConfiguration;
import org.folio.spring.controller.TenantController;
import org.folio.spring.service.TenantService;
import org.folio.tenant.domain.dto.TenantAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("folioTenantController")
@RequestMapping(value = "/_/")
@Log4j2
public class FolioTenantController extends TenantController {

  private final KafkaConfiguration kafka;

  public FolioTenantController(TenantService baseTenantService, KafkaConfiguration kafka) {
    super(baseTenantService);
    this.kafka = kafka;
  }

  @Override
  public ResponseEntity<String> postTenant(TenantAttributes tenantAttributes) {
    var tenantInit = super.postTenant(tenantAttributes);

    if (tenantInit.getStatusCode() == HttpStatus.OK) {
      try {
        kafka.createTopics();
      } catch (Exception e) {
        log.error(e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
      }
    }

    return tenantInit;
  }

}
