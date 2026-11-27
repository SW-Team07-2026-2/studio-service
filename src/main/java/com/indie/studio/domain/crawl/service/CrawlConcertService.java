package com.indie.studio.domain.crawl.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indie.studio.domain.crawl.dto.CrawlDtos.CandidateView;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ConcertItem;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ItemResult;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ReviewedKey;
import com.indie.studio.domain.crawl.entity.CrawlConcertEntity;
import com.indie.studio.domain.crawl.exception.CrawlErrorCode;
import com.indie.studio.domain.crawl.repository.CrawlConcertRepository;
import com.indie.studio.domain.image.ImageGuard;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.client.ServiceClient;
import com.indie.studio.global.client.ServiceClient.Target;
import com.indie.studio.global.common.exception.GeneralException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 수집한 공연 후보를 저장하고 관리자가 보완한 값으로 Concert에 등록합니다.
 * 등록 후 출처가 바뀌면 CHANGED로 표시해 관리자가 수정과 변경 공지를 확인하도록 합니다.
 */
@Service
public class CrawlConcertService {

    private final CrawlConcertRepository candidates;
    private final ServiceClient serviceClient;
    private final ImageGuard images;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CrawlConcertService(CrawlConcertRepository candidates, ServiceClient serviceClient, ImageGuard images, ObjectMapper objectMapper,
                               Clock clock) {
        this.candidates = candidates;
        this.serviceClient = serviceClient;
        this.images = images;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 공연 후보 수집 항목을 저장하고 항목별 결과를 반환합니다.
     *
     * @param items 수집 후보 항목 목록
     */
    @Transactional
    public List<ItemResult> receive(List<ConcertItem> items) {
        List<ItemResult> results = new ArrayList<>();
        for (ConcertItem item : items) {
            String payload = item.concert().toString();
            CrawlConcertEntity found = candidates.findBySourceAndExternalId(item.source(), item.externalId()).orElse(null);
            ItemResult.Result result;
            if (found == null) {
                found = candidates.save(new CrawlConcertEntity(item.source(), item.externalId(), item.sourceHash(), payload, clock.instant()));
                result = ItemResult.Result.CREATED;
            } else if (found.getStatus() == CrawlConcertEntity.Status.REJECTED) {
                result = ItemResult.Result.REVIEWED;
            } else if (item.sourceHash() != null && item.sourceHash().equals(found.getSourceHash())) {
                result = ItemResult.Result.UNCHANGED;
            } else if (found.getStatus() != CrawlConcertEntity.Status.PENDING) {   // 등록한 공연이 바뀜 (APPROVED, CHANGED)
                found.changed(item.sourceHash(), payload, clock.instant());
                result = ItemResult.Result.CHANGED;
            } else {
                found.received(item.sourceHash(), payload, clock.instant());
                result = ItemResult.Result.UPDATED;
            }
            results.add(new ItemResult(item.externalId(), found.getId(), result));
        }
        return results;
    }

    /**
     * 공연 후보 목록을 조회합니다.
     *
     * @param status 조회할 후보 상태
     * @param beforeId 이 ID보다 이전 항목부터 조회
     * @param size 페이지당 조회 개수
     */
    @Transactional(readOnly = true)
    public List<CandidateView> list(CrawlConcertEntity.Status status, Long beforeId, int size) {
        return candidates.page(status, beforeId, PageRequest.of(0, size)).stream().map(this::view).toList();
    }

    /** 공연 후보 검수 완료 후보를 조회합니다. */
    @Transactional(readOnly = true)
    public List<ReviewedKey> reviewed() {
        return candidates.reviewed(CrawlConcertEntity.Status.PENDING).stream()
                .map(r -> new ReviewedKey(r.getSource(), r.getExternalId(), r.getStatus().name())).toList();
    }

    /**
     * Concert 호출은 트랜잭션 밖 - 결과를 받은 뒤 상태만 바꿉니다
     *
     * @param id 공연 후보 ID
     * @param concert 검수한 공연 등록 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    public JsonNode approve(long id, ObjectNode concert, AdminPrincipal admin) {
        CrawlConcertEntity candidate = pending(id);
        concert.put("source", candidate.getSource());
        concert.put("externalId", candidate.getExternalId());
        images.importConcertImages(concert);   // KOPIS 포스터, 소개 이미지 -> 우리 버킷 (못 받으면 400 - 관리자가 직접 올림)
        JsonNode created = serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts", concert, admin);
        candidate.approved(created.path("id").asLong(), admin.id(), clock.instant());
        candidates.save(candidate);
        return created;
    }

    /**
     * 바뀐 공연을 반영했음 - 공연 수정, 변경 공지는 관리자가 따로 합니다 (화면은 previous 와 concert 를 비교해 보여 줌)
     *
     * @param id 공연 후보 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @Transactional
    public CandidateView changeChecked(long id, AdminPrincipal admin) {
        CrawlConcertEntity candidate = candidates.findById(id).orElseThrow(() -> new GeneralException(CrawlErrorCode.CRAWL_CONCERT_NOT_FOUND));
        if (candidate.getStatus() != CrawlConcertEntity.Status.CHANGED) throw new GeneralException(CrawlErrorCode.CRAWL_CONCERT_REVIEWED);
        candidate.changeChecked(admin.id(), clock.instant());
        return view(candidate);
    }

    /**
     * 공연 후보 후보를 적재하지 않도록 처리합니다.
     *
     * @param id 공연 후보 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @Transactional
    public CandidateView reject(long id, AdminPrincipal admin) {
        CrawlConcertEntity candidate = pending(id);
        candidate.rejected(admin.id(), clock.instant());
        return view(candidate);
    }

    private CrawlConcertEntity pending(long id) {
        CrawlConcertEntity candidate = candidates.findById(id).orElseThrow(() -> new GeneralException(CrawlErrorCode.CRAWL_CONCERT_NOT_FOUND));
        if (candidate.getStatus() != CrawlConcertEntity.Status.PENDING) throw new GeneralException(CrawlErrorCode.CRAWL_CONCERT_REVIEWED);
        return candidate;
    }

    private CandidateView view(CrawlConcertEntity c) {
        return new CandidateView(c.getId(), c.getSource(), c.getExternalId(), c.getStatus().name(), json(c, c.getPayloadJson()),
                c.getPreviousJson() == null ? null : json(c, c.getPreviousJson()), c.getReceivedAt(), c.getReviewedBy(), c.getReviewedAt(),
                c.getConcertId());
    }

    private JsonNode json(CrawlConcertEntity c, String text) {
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("crawl_concert " + c.getId() + " payload", e);   // 받을 때 JSON 으로 받아 저장해 일어나지 않음
        }
    }
}
