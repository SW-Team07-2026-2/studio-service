package com.indie.studio.domain.crawl.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indie.studio.domain.crawl.dto.CrawlDtos.CandidateView;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ConcertItem;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ItemResult;
import com.indie.studio.domain.crawl.entity.CrawlConcertEntity;
import com.indie.studio.domain.crawl.exception.CrawlErrorCode;
import com.indie.studio.domain.crawl.repository.CrawlConcertRepository;
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
 * 크롤링 공연 후보 — 받기(크롤러) · 목록 · 등록 · 등록 안 함(공연 관리자).
 * 등록은 관리자가 보완한 공연 값(POST /api/studio/concerts 와 같은 본문)에 후보의 source · externalId 를 넣어 Concert 에 보냅니다.
 * Concert 가 (source, externalId) 를 유일하게 막아 같은 후보를 두 번 등록할 수 없습니다 (동시에 누르면 뒤쪽이 409).
 */
@Service
public class CrawlConcertService {

    private final CrawlConcertRepository candidates;
    private final ServiceClient serviceClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CrawlConcertService(CrawlConcertRepository candidates, ServiceClient serviceClient, ObjectMapper objectMapper, Clock clock) {
        this.candidates = candidates;
        this.serviceClient = serviceClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

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
            } else if (found.getStatus() != CrawlConcertEntity.Status.PENDING) {
                result = ItemResult.Result.REVIEWED;
            } else if (item.sourceHash() != null && item.sourceHash().equals(found.getSourceHash())) {
                result = ItemResult.Result.UNCHANGED;
            } else {
                found.received(item.sourceHash(), payload, clock.instant());
                result = ItemResult.Result.UPDATED;
            }
            results.add(new ItemResult(item.externalId(), found.getId(), result));
        }
        return results;
    }

    @Transactional(readOnly = true)
    public List<CandidateView> list(CrawlConcertEntity.Status status, Long beforeId, int size) {
        return candidates.page(status, beforeId, PageRequest.of(0, size)).stream().map(this::view).toList();
    }

    /** Concert 호출은 트랜잭션 밖 — 결과를 받은 뒤 상태만 바꿉니다 */
    public JsonNode approve(long id, ObjectNode concert, AdminPrincipal admin) {
        CrawlConcertEntity candidate = pending(id);
        concert.put("source", candidate.getSource());
        concert.put("externalId", candidate.getExternalId());
        JsonNode created = serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts", concert, admin);
        candidate.approved(created.path("id").asLong(), admin.id(), clock.instant());
        candidates.save(candidate);
        return created;
    }

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
        JsonNode concert;
        try {
            concert = objectMapper.readTree(c.getPayloadJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("crawl_concert " + c.getId() + " payload", e);   // 받을 때 JSON 으로 받아 저장해 일어나지 않음
        }
        return new CandidateView(c.getId(), c.getSource(), c.getExternalId(), c.getStatus().name(), concert,
                c.getReceivedAt(), c.getReviewedBy(), c.getReviewedAt(), c.getConcertId());
    }
}
