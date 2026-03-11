package com.visa.nucleus.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReactionEventRepository extends JpaRepository<ReactionEvent, String> {
}
