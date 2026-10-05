package com.trustagent.core.internalpolicy.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal-policy/checklists")
class InternalPolicyApplicableController {

    private final InternalPolicyApplicableService service;

    InternalPolicyApplicableController(InternalPolicyApplicableService service) {
        this.service = service;
    }

    @GetMapping("/{familyId}/applicable")
    InternalPolicyApplicableState getApplicable(
            @PathVariable String familyId,
            @RequestParam(required = false) String businessDate,
            @RequestParam(required = false) String knownAt) {
        return service.get(familyId, businessDate, knownAt);
    }
}
