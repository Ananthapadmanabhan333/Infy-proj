package com.nexus360.statuspage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StatusSubscriberRepository extends JpaRepository<StatusSubscriber, Long> {

    @Query("select s from StatusSubscriber s where lower(s.email) = lower(:email)")
    Optional<StatusSubscriber> findByEmail(@Param("email") String email);

    Optional<StatusSubscriber> findByConfirmTokenHash(String hash);

    Optional<StatusSubscriber> findByUnsubscribeTokenHash(String hash);

    List<StatusSubscriber> findByConfirmedAtIsNotNullOrderByIdAsc();

    long countByConfirmedAtIsNotNull();

    long countByConfirmedAtIsNull();
}
