package com.indie.studio.domain.crawl.repository;

import com.indie.studio.domain.crawl.entity.CrawlConcertEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CrawlConcertRepository extends JpaRepository<CrawlConcertEntity, Long> {

    Optional<CrawlConcertEntity> findBySourceAndExternalId(String source, String externalId);

    /** 최신순 — beforeId 보다 오래된 것부터 (커서) */
    @Query("select c from CrawlConcertEntity c where c.status = :status and (:beforeId is null or c.id < :beforeId) order by c.id desc")
    List<CrawlConcertEntity> page(CrawlConcertEntity.Status status, Long beforeId, Pageable pageable);
}
