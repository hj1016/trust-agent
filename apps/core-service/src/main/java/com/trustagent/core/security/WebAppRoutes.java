package com.trustagent.core.security;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 업무지원 화면(TASK-017b)은 Core가 정적 자원으로 제공한다(ADR-014 1항). 화면 경로는 index.html로 넘기고 화면이 API로 데이터를 읽는다.
 * 경로를 명시적으로 나열한다(모든 GET을 넘기지 않는다). /api/**, /actuator/**는 해당하지 않는다.
 */
@Controller
public class WebAppRoutes {

    public static final String[] STATIC_PATTERNS = {"/", "/index.html", "/assets/**", "/favicon.svg"};
    public static final String[] SCREEN_PATTERNS = {"/login", "/consultations", "/consultations/*", "/reviews"};

    @GetMapping({"/login", "/consultations", "/consultations/{consultationId}", "/reviews"})
    public String screen() {
        return "forward:/index.html";
    }
}
