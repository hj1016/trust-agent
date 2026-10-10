package com.trustagent.core.consultation;

import com.trustagent.core.json.CanonicalJsonHasher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 합성 기업·신청 자료 적재(TASK-017a A안). datasets/synthetic/work/{companies,applications}의 JSON을 계약 필수 필드로 검증하고
 * canonical sha256(AI 서비스 ids.py와 같은 규칙)과 함께 업무 DB에 넣는다. 같은 ID·같은 해시는 건너뛰고(멱등), 같은 ID·다른 내용은 거부한다.
 * Core가 신청·상담 자료의 원장이 되는 첫 단계이며 AI 서비스는 당분간 같은 JSON을 읽되 해시 대조로 일치를 강제한다.
 */
public final class SyntheticWorkLoader {

    public record Result(int companiesInserted, int applicationsInserted, int skipped) {}

    private static final Pattern COMPANY_ID = Pattern.compile("^SW-COMPANY-[0-9]{3}$");
    private static final Pattern APPLICATION_ID = Pattern.compile("^SW-APPLICATION-[0-9]{3}$");
    private static final Pattern PRODUCT_KEY = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final CanonicalJsonHasher hasher;
    private final Clock clock;

    public SyntheticWorkLoader(JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.hasher = new CanonicalJsonHasher(mapper);
        this.clock = clock;
    }

    public Result load(Path repositoryRoot) {
        Path datasetRoot = Files.isDirectory(repositoryRoot.resolve("datasets")) ? repositoryRoot.resolve("datasets") : repositoryRoot;
        List<JsonNode> companies = readAll(datasetRoot.resolve("synthetic/work/companies"));
        List<JsonNode> applications = readAll(datasetRoot.resolve("synthetic/work/applications"));
        return transaction.execute(status -> {
            jdbc.sql("select pg_advisory_xact_lock(hashtext('trust-agent-synthetic-work'))").query().singleRow();
            OffsetDateTime loadedAt = clock.instant().atOffset(ZoneOffset.UTC);
            int companyRows = 0;
            int applicationRows = 0;
            int skipped = 0;
            for (JsonNode company : companies) {
                validateCommon(company, "company");
                String id = require(company, "company_id", COMPANY_ID);
                String hash = hasher.canonicalize(company).sha256();
                String existing = jdbc.sql("select source_hash from synthetic_work_company where company_id = :id").param("id", id)
                        .query(String.class).optional().orElse(null);
                if (existing != null) {
                    if (!existing.equals(hash)) throw new IllegalStateException("SYNTHETIC_WORK_CONFLICT: 같은 기업 ID의 다른 내용: " + id);
                    skipped++;
                    continue;
                }
                jdbc.sql("""
                        insert into synthetic_work_company (company_id, dataset_class, legal_name, business_id, business_type, industry, established_on, source_hash, loaded_at)
                        values (:id, 'SYNTHETIC_WORK', :legalName, :businessId, :businessType, :industry, :establishedOn, :hash, :loadedAt)
                        """)
                        .param("id", id).param("legalName", text(company, "legal_name")).param("businessId", text(company, "business_id"))
                        .param("businessType", text(company, "business_type")).param("industry", text(company, "industry"))
                        .param("establishedOn", LocalDate.parse(text(company, "established_on"))).param("hash", hash).param("loadedAt", loadedAt)
                        .update();
                companyRows++;
            }
            for (JsonNode application : applications) {
                validateCommon(application, "application");
                String id = require(application, "application_id", APPLICATION_ID);
                String companyId = require(application, "company_id", COMPANY_ID);
                String productKey = require(application, "product_key", PRODUCT_KEY);
                String hash = hasher.canonicalize(application).sha256();
                String existing = jdbc.sql("select source_hash from synthetic_work_application where application_id = :id").param("id", id)
                        .query(String.class).optional().orElse(null);
                if (existing != null) {
                    if (!existing.equals(hash)) throw new IllegalStateException("SYNTHETIC_WORK_CONFLICT: 같은 신청 ID의 다른 내용: " + id);
                    skipped++;
                    continue;
                }
                long amount = application.path("requested_amount_krw").asLong(0);
                if (amount <= 0) throw new IllegalStateException("합성 신청의 requested_amount_krw가 올바르지 않습니다: " + id);
                jdbc.sql("""
                        insert into synthetic_work_application (application_id, dataset_class, company_id, product_key, requested_at, requested_amount_krw, purpose, status, source_hash, loaded_at)
                        values (:id, 'SYNTHETIC_WORK', :companyId, :productKey, :requestedAt, :amount, :purpose, :status, :hash, :loadedAt)
                        """)
                        .param("id", id).param("companyId", companyId).param("productKey", productKey)
                        .param("requestedAt", OffsetDateTime.parse(text(application, "requested_at"))).param("amount", amount)
                        .param("purpose", text(application, "purpose")).param("status", text(application, "status"))
                        .param("hash", hash).param("loadedAt", loadedAt)
                        .update();
                applicationRows++;
            }
            return new Result(companyRows, applicationRows, skipped);
        });
    }

    private List<JsonNode> readAll(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("합성 자료 디렉터리가 없습니다: " + directory);
        }
        List<JsonNode> nodes = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                nodes.add(mapper.readTree(Files.readString(file)));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("합성 자료를 읽을 수 없습니다: " + directory, exception);
        }
        return nodes;
    }

    private static void validateCommon(JsonNode node, String kind) {
        if (!node.isObject() || !"SYNTHETIC_WORK".equals(text(node, "dataset_class")) || !node.path("synthetic").asBoolean(false)
                || text(node, "disclaimer").isBlank()) {
            throw new IllegalStateException("합성 " + kind + " 자료는 dataset_class SYNTHETIC_WORK, synthetic true, disclaimer가 있어야 합니다.");
        }
    }

    private static String require(JsonNode node, String field, Pattern pattern) {
        String value = text(node, field);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalStateException("합성 자료 필드 형식 오류: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? "" : value.stringValue();
    }
}
