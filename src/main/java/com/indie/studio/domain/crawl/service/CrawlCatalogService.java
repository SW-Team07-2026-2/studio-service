package com.indie.studio.domain.crawl.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ApproveResult;
import com.indie.studio.domain.crawl.dto.CrawlDtos.CatalogCandidateView;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ItemResult;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ReviewedKey;
import com.indie.studio.domain.crawl.entity.CrawlCatalogEntity;
import com.indie.studio.domain.crawl.entity.CrawlCatalogEntity.Status;
import com.indie.studio.domain.crawl.entity.CrawlCatalogEntity.Type;
import com.indie.studio.domain.crawl.exception.CrawlErrorCode;
import com.indie.studio.domain.crawl.repository.CrawlCatalogRepository;
import com.indie.studio.domain.image.ImageGuard;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.client.ServiceClient;
import com.indie.studio.global.client.ServiceClient.Target;
import com.indie.studio.global.common.exception.CommonErrorCode;
import com.indie.studio.global.common.exception.GeneralException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 수집한 아티스트와 앨범 후보를 저장하고 관리자 승인 후 Catalog에 적재합니다.
 * 해시가 같은 후보는 유지하고 승인 후 값이 바뀌면 다시 검수합니다. 앨범은 승인된 아티스트 ID를 사용합니다.
 */
@Service
public class CrawlCatalogService {

    private static final Logger log = LoggerFactory.getLogger(CrawlCatalogService.class);

    private final CrawlCatalogRepository candidates;
    private final ServiceClient serviceClient;
    private final ImageGuard images;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CrawlCatalogService(CrawlCatalogRepository candidates, ServiceClient serviceClient, ImageGuard images, ObjectMapper objectMapper,
                               Clock clock) {
        this.candidates = candidates;
        this.serviceClient = serviceClient;
        this.images = images;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 아티스트와 앨범 후보 수집 항목을 저장하고 항목별 결과를 반환합니다.
     *
     * @param type 수집 후보 종류
     * @param items 수집 후보 항목 목록
     */
    @Transactional
    public List<ItemResult> receive(Type type, List<ObjectNode> items) {
        items.forEach(item -> checkKeys(type, item));
        List<ItemResult> results = new ArrayList<>();
        for (ObjectNode item : items) {
            String source = item.get("source").asText();
            String externalId = item.get("external_id").asText();
            String hash = item.hasNonNull("source_hash") ? item.get("source_hash").asText() : null;
            CrawlCatalogEntity found = candidates.findByTypeAndSourceAndExternalId(type, source, externalId).orElse(null);
            ItemResult.Result result;
            if (found == null) {
                found = candidates.save(new CrawlCatalogEntity(type, source, externalId, hash, item.toString(), clock.instant()));
                result = ItemResult.Result.CREATED;
            } else if (found.getStatus() == Status.REJECTED) {
                result = ItemResult.Result.REVIEWED;
            } else if (hash != null && hash.equals(found.getSourceHash())) {
                result = ItemResult.Result.UNCHANGED;
            } else {
                found.received(hash, item.toString(), clock.instant());
                result = ItemResult.Result.UPDATED;
            }
            results.add(new ItemResult(externalId, found.getId(), result));
        }
        return results;
    }

    /**
     * 아티스트와 앨범 후보 목록을 조회합니다.
     *
     * @param status 조회할 후보 상태
     * @param type 수집 후보 종류
     * @param beforeId 이 ID보다 이전 항목부터 조회
     * @param size 페이지당 조회 개수
     */
    @Transactional(readOnly = true)
    public List<CatalogCandidateView> list(Status status, Type type, Long beforeId, int size) {
        return candidates.page(status, type, beforeId, PageRequest.of(0, size)).stream().map(this::view).toList();
    }

    /**
     * 아티스트와 앨범 후보 검수 완료 후보를 조회합니다.
     *
     * @param type 수집 후보 종류
     */
    @Transactional(readOnly = true)
    public List<ReviewedKey> reviewed(Type type) {
        return candidates.reviewed(type, Status.PENDING).stream()
                .map(r -> new ReviewedKey(r.getSource(), r.getExternalId(), r.getStatus().name())).toList();
    }

    /**
     * edited 가 있으면 관리자가 고친 항목으로 (받은 항목과 같은 모양) - 저장해 두고 그 값으로 보냅니다
     *
     * @param id 아티스트와 앨범 후보 ID
     * @param edited 관리자가 검수하거나 수정한 값
     * @param admin 요청한 관리자와 권한 정보
     */
    public CatalogCandidateView approve(long id, ObjectNode edited, AdminPrincipal admin) {
        CrawlCatalogEntity candidate = pending(id);
        if (edited != null && !edited.isEmpty()) {
            edited.put("source", candidate.getSource());
            edited.put("external_id", candidate.getExternalId());
            checkKeys(candidate.getType(), edited);
            candidate.edited(edited.toString());
        }
        ObjectNode item = (ObjectNode) read(candidate);
        requirePrices(item.path(candidate.getType() == Type.ALBUM ? "physical_albums" : "goods"));
        images.checkCatalogItem(item);
        if (candidate.getType() == Type.ALBUM) item = withArtistId(item);
        item.remove("evidence");   // 출처는 관리자 확인용 - Catalog 로는 보내지 않음
        // 굿즈, 판본 상세 이미지는 크롤러가 상세 설명을 쓸 때 읽은 자료 (관리자 확인용) - Catalog 로는 보내지 않음 (상품은 대표 이미지만)
        for (String products : List.of("goods", "physical_albums")) {
            item.path(products).forEach(p -> { if (p instanceof ObjectNode node) node.remove("detail_image_urls"); });
        }

        String path = candidate.getType() == Type.ARTIST ? "/internal/admin/catalog/ingest/artists" : "/internal/admin/catalog/ingest/albums";
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("items").add(item);
        JsonNode outcome;
        try {
            outcome = serviceClient.send(Target.CATALOG, HttpMethod.POST, path, body, admin).path("items").path(0);
        } catch (GeneralException e) {
            candidate.failed(e.getErrorCode().getCode() + ": " + e.getMessage());
            candidates.save(candidate);
            throw e;
        }
        if (!outcome.hasNonNull("id") || "FAILED".equals(outcome.path("result").asText())) {
            String error = outcome.path("error").asText("Catalog 결과 없음");
            candidate.failed(error);
            candidates.save(candidate);
            throw new GeneralException(CrawlErrorCode.CRAWL_CATALOG_FAILED, error);
        }
        candidate.approved(outcome.get("id").asLong(), admin.id(), clock.instant());
        log.info("crawl_catalog_approved id={} type={} catalog_id={} result={}", id, candidate.getType(),
                candidate.getCatalogId(), outcome.path("result").asText());
        return view(candidates.save(candidate));
    }

    /**
     * 아티스트부터 (앨범이 같은 묶음의 아티스트를 가리킬 수 있음), 하나가 실패해도 나머지는 계속
     *
     * @param ids 승인할 수집 후보 ID 목록
     * @param admin 요청한 관리자와 권한 정보
     */
    public List<ApproveResult> approveMany(List<Long> ids, AdminPrincipal admin) {
        List<CrawlCatalogEntity> found = candidates.findAllById(ids.stream().distinct().toList()).stream()
                .sorted(Comparator.comparing(CrawlCatalogEntity::getType).thenComparing(CrawlCatalogEntity::getId)).toList();
        List<ApproveResult> results = new ArrayList<>();
        for (CrawlCatalogEntity c : found) {
            try {
                CatalogCandidateView v = approve(c.getId(), null, admin);
                results.add(new ApproveResult(c.getId(), true, v.catalogId(), null, null));
            } catch (GeneralException e) {
                results.add(new ApproveResult(c.getId(), false, null, e.getErrorCode().getCode(), e.getMessage()));
            }
        }
        ids.stream().distinct().filter(i -> found.stream().noneMatch(c -> c.getId().equals(i))).forEach(i -> results.add(new ApproveResult(i, false, null,
                CrawlErrorCode.CRAWL_CATALOG_NOT_FOUND.getCode(), CrawlErrorCode.CRAWL_CATALOG_NOT_FOUND.getMessage())));
        return results;
    }

    /**
     * 아티스트와 앨범 후보 후보를 적재하지 않도록 처리합니다.
     *
     * @param id 아티스트와 앨범 후보 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @Transactional
    public CatalogCandidateView reject(long id, AdminPrincipal admin) {
        CrawlCatalogEntity candidate = pending(id);
        candidate.rejected(admin.id(), clock.instant());
        return view(candidate);
    }

    /** 앨범: 관리자가 artist_id 를 직접 넣었으면 그대로, 아니면 artist_ref 의 아티스트 후보가 Catalog 에 들어가 있어야 합니다 */
    private ObjectNode withArtistId(ObjectNode item) {
        JsonNode ref = item.remove("artist_ref");
        if (item.path("artist_id").asLong() > 0) return item;
        CrawlCatalogEntity artist = ref == null ? null : candidates.findByTypeAndSourceAndExternalId(Type.ARTIST,
                ref.path("source").asText(), ref.path("external_id").asText()).orElse(null);
        if (artist == null || artist.getCatalogId() == null) {
            throw new GeneralException(CrawlErrorCode.CRAWL_ARTIST_NOT_APPROVED)
                    .with("artistCandidateId", artist == null ? null : artist.getId());
        }
        item.put("artist_id", artist.getCatalogId());
        return item;
    }

    /**
     * 판매가는 관리자가 넣는 값 - 크롤러는 null 로 보냅니다. 넣은 값이 0 이하면 거절 (0원 상품 방지).
     * null 은 Catalog 가 저장된 판매가를 그대로 두고, 새 옵션이면 Catalog 가 거절합니다 (사유는 last_error, 여러 건 승인도 같음).
     */
    private static void requirePrices(JsonNode products) {
        for (JsonNode product : products) {
            for (JsonNode option : product.path("options")) {
                JsonNode price = option.get("price");
                if (price != null && !price.isNull() && price.asLong(0) <= 0) throw new GeneralException(CrawlErrorCode.CRAWL_PRICE_REQUIRED);
            }
        }
    }

    /** 열쇠만 Studio 가 봅니다 - 나머지 값은 승인할 때 Catalog 가 검사 */
    private static void checkKeys(Type type, ObjectNode item) {
        if (!text(item.get("source"), 32) || !text(item.get("external_id"), 128)) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "items[].source(32자 이하) · external_id(128자 이하)가 필요합니다");
        }
        if (item.hasNonNull("source_hash") && !text(item.get("source_hash"), 128)) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "items[].source_hash 는 128자 이하입니다");
        }
        if (type == Type.ALBUM && !(item.path("artist_id").asLong() > 0)) {
            JsonNode ref = item.get("artist_ref");
            if (ref == null || !text(ref.get("source"), 32) || !text(ref.get("external_id"), 128)) {
                throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "앨범은 artist_ref: { source, external_id } 가 필요합니다");
            }
        }
    }

    private static boolean text(JsonNode node, int max) {
        return node != null && node.isTextual() && !node.asText().isBlank() && node.asText().length() <= max;
    }

    private CrawlCatalogEntity pending(long id) {
        CrawlCatalogEntity candidate = candidates.findById(id).orElseThrow(() -> new GeneralException(CrawlErrorCode.CRAWL_CATALOG_NOT_FOUND));
        if (candidate.getStatus() != Status.PENDING) throw new GeneralException(CrawlErrorCode.CRAWL_CATALOG_REVIEWED);
        return candidate;
    }

    private JsonNode read(CrawlCatalogEntity c) {
        try {
            return objectMapper.readTree(c.getPayloadJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("crawl_catalog " + c.getId() + " payload", e);   // 받을 때 JSON 객체로 받아 저장해 일어나지 않음
        }
    }

    private CatalogCandidateView view(CrawlCatalogEntity c) {
        return new CatalogCandidateView(c.getId(), c.getType().name(), c.getSource(), c.getExternalId(), c.getStatus().name(), read(c),
                c.getReceivedAt(), c.getReviewedBy(), c.getReviewedAt(), c.getCatalogId(), c.getLastError());
    }
}
