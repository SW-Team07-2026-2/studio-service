package com.indie.studio.domain.stage.repository;

import com.indie.studio.domain.stage.entity.StageJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StageJobRepository extends JpaRepository<StageJobEntity, Long> {

    List<StageJobEntity> findTop50ByVenueIdOrderByIdDesc(long venueId);

    List<StageJobEntity> findByStatusIn(Collection<StageJobEntity.Status> statuses);
}
