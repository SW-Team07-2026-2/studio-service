package com.indie.studio.domain.crawl.repository;

import com.indie.studio.domain.crawl.entity.CrawlConcertEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CrawlConcertRepository extends JpaRepository<CrawlConcertEntity, Long> {

    Optional<CrawlConcertEntity> findBySourceAndExternalId(String source, String externalId);

    /** 최신순 - beforeId 보다 오래된 것부터 (커서) */
    @Query("select c from CrawlConcertEntity c where c.status = :status and (:beforeId is null or c.id < :beforeId) order by c.id desc")
    List<CrawlConcertEntity> page(CrawlConcertEntity.Status status, Long beforeId, Pageable pageable);

    /** 관리자가 처리한 후보의 키, 상태만 (본문 없이) - 크롤러가 다시 찾지 않게 */
    @Query("select c.source as source, c.externalId as externalId, c.status as status from CrawlConcertEntity c where c.status <> :pending")
    List<Reviewed> reviewed(CrawlConcertEntity.Status pending);

    interface Reviewed {
        String getSource();
        String getExternalId();
        CrawlConcertEntity.Status getStatus();
    }
}
