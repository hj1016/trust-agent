package com.trustagent.core.preparation;

import com.trustagent.core.json.CanonicalJsonHasher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 상품별 공문군 매핑 파일(datasets/synthetic/work/consultation-family-mapping.json)을 Core에 적재한다.
 * 현재는 합성 시나리오의 사용자 승인 설정이다. AI 기록 경로와 별개의 bootstrap 경로이며 기록 토큰으로는 바꿀 수 없다.
 * 같은 해시가 이미 있으면 아무것도 하지 않는다(멱등). 매핑 표는 append-only라 변경은 새 버전·새 해시로만 들어간다.
 */
public final class ConsultationFamilyMappingLoader {

    public record Result(String mappingHash, String mappingVersion, int insertedRows) {}

    static final String RELATIVE_PATH = "synthetic/work/consultation-family-mapping.json";
    private static final Pattern VERSION = Pattern.compile("^v[1-9][0-9]*$");
    private static final Pattern PRODUCT_KEY = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern FAMILY_ID = Pattern.compile("^SIN-[A-Z0-9-]+$");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CanonicalJsonHasher hasher;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ConsultationFamilyMappingLoader(JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.hasher = new CanonicalJsonHasher(mapper);
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    public Result load(Path repositoryRoot) {
        Path datasetRoot = Files.isDirectory(repositoryRoot.resolve("datasets")) ? repositoryRoot.resolve("datasets") : repositoryRoot;
        Path file = datasetRoot.resolve(RELATIVE_PATH);
        JsonNode json;
        try {
            json = mapper.readTree(Files.readString(file));
        } catch (IOException exception) {
            throw new IllegalStateException("공문군 매핑 파일을 읽을 수 없습니다: " + file, exception);
        }
        validate(json, file);
        String hash = hasher.canonicalize(json).sha256();
        String version = json.get("mapping_version").stringValue();
        return transaction.execute(status -> {
            jdbc.sql("select pg_advisory_xact_lock(hashtext('trust-agent-consultation-family-mapping'))").query().singleRow();
            int existing = jdbc.sql("select count(*) from consultation_family_mapping where mapping_hash = :hash")
                    .param("hash", hash).query(Integer.class).single();
            if (existing > 0) {
                return new Result(hash, version, 0);
            }
            int inserted = 0;
            var loadedAt = clock.instant().atOffset(ZoneOffset.UTC);
            for (JsonNode product : json.get("products")) {
                for (JsonNode family : product.get("families")) {
                    jdbc.sql("""
                                    insert into consultation_family_mapping (
                                        mapping_hash, mapping_version, product_key, family_id, required, family_order, loaded_at)
                                    values (:hash, :version, :product, :family, :required, :order, :loadedAt)
                                    """)
                            .param("hash", hash)
                            .param("version", version)
                            .param("product", product.get("product_key").stringValue())
                            .param("family", family.get("family_id").stringValue())
                            .param("required", family.get("required").booleanValue())
                            .param("order", family.get("order").intValue())
                            .param("loadedAt", loadedAt)
                            .update();
                    inserted++;
                }
            }
            return new Result(hash, version, inserted);
        });
    }

    private static void validate(JsonNode json, Path file) {
        if (!json.isObject() || !"SYNTHETIC_WORK".equals(text(json, "dataset_class"))
                || json.get("synthetic") == null || !json.get("synthetic").asBoolean()) {
            throw new IllegalStateException("공문군 매핑은 dataset_class SYNTHETIC_WORK, synthetic true여야 합니다: " + file);
        }
        String disclaimer = text(json, "disclaimer");
        if (disclaimer == null || !disclaimer.contains("합성")) {
            throw new IllegalStateException("공문군 매핑 면책 문구가 없습니다: " + file);
        }
        String version = text(json, "mapping_version");
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalStateException("mapping_version 형식이 올바르지 않습니다: " + file);
        }
        JsonNode products = json.get("products");
        if (products == null || !products.isArray() || products.isEmpty()) {
            throw new IllegalStateException("products가 비어 있습니다: " + file);
        }
        for (JsonNode product : products) {
            String key = text(product, "product_key");
            if (key == null || !PRODUCT_KEY.matcher(key).matches()) {
                throw new IllegalStateException("product_key 형식이 올바르지 않습니다: " + file);
            }
            JsonNode families = product.get("families");
            if (families == null || !families.isArray() || families.isEmpty()) {
                throw new IllegalStateException("families가 비어 있습니다: " + key);
            }
            for (JsonNode family : families) {
                String familyId = text(family, "family_id");
                if (familyId == null || !FAMILY_ID.matcher(familyId).matches()
                        || family.get("required") == null || !family.get("required").isBoolean()
                        || family.get("order") == null || !family.get("order").isIntegralNumber()) {
                    throw new IllegalStateException("공문군 항목 형식이 올바르지 않습니다: " + key);
                }
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.stringValue();
    }
}
