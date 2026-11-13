package com.indie.studio.domain.account.repository;

import com.indie.studio.domain.account.entity.AdminAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminAccountRepository extends JpaRepository<AdminAccountEntity, Long> {
    Optional<AdminAccountEntity> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);
}
