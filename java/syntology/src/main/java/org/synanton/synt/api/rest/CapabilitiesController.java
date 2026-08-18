package org.synanton.synt.api.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.synt.domain.service.OntologyService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ontology")
public class CapabilitiesController {

    private final OntologyService ontologyService;

    public CapabilitiesController(OntologyService ontologyService) {
        this.ontologyService = ontologyService;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return ontologyService.getCapabilities();
    }
}
