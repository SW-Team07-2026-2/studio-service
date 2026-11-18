package com.indie.studio.domain.crawl.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ApproveResult;
import com.indie.studio.domain.crawl.dto.CrawlDtos.CatalogCandidateView;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ItemResult;
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
 * 크롤링 아티스트 · 앨범 후보 — 받기(크롤러) · 목록 · 승인 · 적재 안 함(상품 관리자). 11/17 결정: Catalog 로 바로 보내지 않고 승인한 것만.
 * <pre>
 * 받기   (type, source, external_id) 가 열쇠. 처음 → PENDING · 같은 source_hash → UNCHANGED · REJECTED 는 그대로(REVIEWED)
 *        승인된 것이 다른 값으로 오면 다시 PENDING (변경도 승인을 거침 — 새 굿즈 · 트랙 · 피지컬)
 * 승인   그 한 건을 Catalog /internal/admin/catalog/ingest/{artists|albums} 로. 앨범은 artist_ref → 승인된 아티스트의 catalog_id
 *        피지컬 앨범 · 굿즈 옵션 가격이 0 이하면 거절 · null 이면 Catalog 가 저장된 판매가를 그대로 두고 새 옵션이면 거절 (11/18: 판매가는 관리자 값)
 *        이미지 칸은 우리 버킷에 올라간 주소만 (ImageGuard · null 이면 Catalog 가 저장된 이미지를 그대로 둠) · 출처(evidence)는 빼고 보냄
 *        Catalog 가 항목을 거절(FAILED)하거나 400 이면 PENDING 그대로 + last_error
 * </pre>
 * Catalog 호출은 트랜잭션 밖 — 결과를 받은 뒤 상태만 바꿉니다. 두 관리자가 동시에 승인해도 Catalog 적재가 (source, external_id) 로 멱등이라 같은 결과입니다.
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

    @Transactional(readOnly = true)
    public List<CatalogCandidateView> list(Status status, Type type, Long beforeId, int size) {
        return candidates.page(status, type, beforeId, PageRequest.of(0, size)).stream().map(this::view).toList();
    }

    /** edited 가 있으면 관리자가 고친 항목으로 (받은 항목과 같은 모양) — 저장해 두고 그 값으로 보냅니다 */
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
        item.remove("evidence");   // 출처는 관리자 확인용 — Catalog 로는 보내지 않음

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

    /** 아티스트부터 (앨범이 같은 묶음의 아티스트를 가리킬 수 있음) · 하나가 실패해도 나머지는 계속 */
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
     * 판매가는 관리자가 넣는 값 (11/18) — 크롤러는 null 로 보냅니다. 넣은 값이 0 이하면 거절 (0원 상품 방지).
     * null 은 Catalog 가 저장된 판매가를 그대로 두고, 새 옵션이면 Catalog 가 거절합니다 (사유는 last_error · 여러 건 승인도 같음).
     */
    private static void requirePrices(JsonNode products) {
        for (JsonNode product : products) {
            for (JsonNode option : product.path("options")) {
                JsonNode price = option.get("price");
                if (price != null && !price.isNull() && price.asLong(0) <= 0) throw new GeneralException(CrawlErrorCode.CRAWL_PRICE_REQUIRED);
            }
        }
    }

    /** 열쇠만 Studio 가 봅니다 — 나머지 값은 승인할 때 Catalog 가 검사 */
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
