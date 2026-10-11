package com.trustagent.core.consultation;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 등록된 합성 신청 목록(TASK-017b, STAFF 활성). 상담 건 생성 화면에서 신청을 고른다. 쓰기 경로는 없다. */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private final ConsultationService consultations;

    public ApplicationController(ConsultationService consultations) {
        this.consultations = consultations;
    }

    @GetMapping
    public Map<String, List<ConsultationService.ApplicationSummary>> applications() {
        return Map.of("applications", consultations.applications());
    }
}
