package com.trustagent.core.support;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 세션 로그인 HTTP 클라이언트(테스트용). 쿠키 CSRF 토큰을 읽어 헤더로 보내고 세션 쿠키를 유지한다.
 * 로그인 흐름: GET /api/v1/session(401, XSRF-TOKEN 쿠키 발급) → POST /login(form + X-XSRF-TOKEN) → 200.
 */
public final class SessionClient {

    private final HttpClient http;
    private final CookieManager cookies;
    private final String baseUrl;
    private HttpResponse<String> loginResponse;

    private SessionClient(int port) {
        this.cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    public static SessionClient anonymous(int port) {
        return new SessionClient(port);
    }

    public static SessionClient login(int port, String userId, String password) throws Exception {
        SessionClient client = new SessionClient(port);
        client.get("/api/v1/session");
        client.loginResponse = client.postForm("/login", "username=" + encode(userId) + "&password=" + encode(password));
        if (client.loginResponse.statusCode() != 200) {
            throw new IllegalStateException("로그인 실패 " + client.loginResponse.statusCode() + " " + client.loginResponse.body());
        }
        // 로그인 성공 시 CSRF 토큰이 회전되므로 한 번 조회해 새 XSRF-TOKEN 쿠키를 받는다(브라우저가 화면을 다시 읽는 것과 같다).
        client.get("/api/v1/session");
        return client;
    }

    public static SessionClient staff(int port, JdbcClient control) throws Exception {
        return login(port, TestUsers.STAFF, TestUsers.ensureStaff(control));
    }

    public static SessionClient reviewer(int port, JdbcClient control) throws Exception {
        return login(port, TestUsers.REVIEWER, TestUsers.ensureReviewer(control));
    }

    public static SessionClient both(int port, JdbcClient control) throws Exception {
        return login(port, TestUsers.BOTH, TestUsers.ensureBoth(control));
    }

    public HttpResponse<String> loginResponse() {
        return loginResponse;
    }

    public HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> postJson(String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        csrfToken().ifPresent(token -> request.header("X-XSRF-TOKEN", token));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** CSRF 헤더 없이 보내는 상태 변경 요청(거부 검증용). */
    public HttpResponse<String> postJsonWithoutCsrf(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 로그인 뒤 같은 세션에서 다시 로그인(세션 ID 재발급 검증용). 토큰 회전을 위해 조회 뒤 전송한다. */
    public HttpResponse<String> relogin(String userId, String password) throws Exception {
        get("/api/v1/session");
        HttpResponse<String> response = postForm("/login", "username=" + encode(userId) + "&password=" + encode(password));
        get("/api/v1/session");
        return response;
    }

    public HttpResponse<String> postForm(String path, String form) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        csrfToken().ifPresent(token -> request.header("X-XSRF-TOKEN", token));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> logout() throws Exception {
        return postForm("/logout", "");
    }

    public Optional<String> csrfToken() {
        return cookie("XSRF-TOKEN");
    }

    public Optional<String> sessionId() {
        return cookie("JSESSIONID");
    }

    public List<HttpCookie> cookies() {
        return cookies.getCookieStore().getCookies();
    }

    private Optional<String> cookie(String name) {
        return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals(name)).map(HttpCookie::getValue).findFirst();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
