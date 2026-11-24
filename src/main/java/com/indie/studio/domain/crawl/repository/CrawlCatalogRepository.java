package com.indie.studio.domain.crawl.repository;

import com.indie.studio.domain.crawl.entity.CrawlCatalogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CrawlCatalogRepository extends JpaRepository<CrawlCatalogEntity, Long> {

    Optional<CrawlCatalogEntity> findByTypeAndSourceAndExternalId(CrawlCatalogEntity.Type type, String source, String externalId);

    /** 최신순 — type 이 null 이면 둘 다 · beforeId 보다 오래된 것부터 (커서) */
    @Query("select c from CrawlCatalogEntity c where c.status = :status and (:type is null or c.type = :type)"
            + " and (:beforeId is null or c.id < :beforeId) order by c.id desc")
    List<CrawlCatalogEntity> page(CrawlCatalogEntity.Status status, CrawlCatalogEntity.Type type, Long beforeId, Pageable pageable);

    /** 관리자가 처리한 후보의 키 · 상태만 (본문 없이) — 크롤러가 다시 찾지 않게 (11/24) */
    @Query("select c.source as source, c.externalId as externalId, c.status as status from CrawlCatalogEntity c"
            + " where c.type = :type and c.status <> :pending")
    List<Reviewed> reviewed(CrawlCatalogEntity.Type type, CrawlCatalogEntity.Status pending);

    interface Reviewed {
        String getSource();
        String getExternalId();
        CrawlCatalogEntity.Status getStatus();
    }
}
