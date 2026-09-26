package com.trustagent.core.publicproduct.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public-products")
class PublicProductObservedStateController {

    private final PublicProductObservedStateService service;

    PublicProductObservedStateController(PublicProductObservedStateService service) {
        this.service = service;
    }

    @GetMapping("/{productKey}/observed-state")
    PublicProductObservedState getObservedState(
            @PathVariable String productKey,
            @RequestParam(required = false) String asOf) {
        return service.get(productKey, asOf);
    }
}
