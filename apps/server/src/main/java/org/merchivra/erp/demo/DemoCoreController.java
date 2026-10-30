package org.merchivra.erp.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Only available when local synthetic-data demo endpoints are enabled. */
@RestController
@RequestMapping("/api/v1/demo")
@ConditionalOnProperty(prefix = "merchivra.demo", name = "enabled", havingValue = "true")
public class DemoCoreController {
    private final DemoCoreImporter importer;

    public DemoCoreController(DemoCoreImporter importer) {
        this.importer = importer;
    }

    @GetMapping("/core-status")
    public DemoCoreImporter.CoreStatus status() {
        return importer.status();
    }
}
