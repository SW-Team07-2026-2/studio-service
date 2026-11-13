package com.indie.studio.domain.stage.repository;

import com.indie.studio.domain.stage.entity.StageJobImageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StageJobImageRepository extends JpaRepository<StageJobImageEntity, Long> {

    Optional<StageJobImageEntity> findByJobIdAndKind(long jobId, StageJobImageEntity.Kind kind);

    boolean existsByJobIdAndKind(long jobId, StageJobImageEntity.Kind kind);
}
