package com.nexus360.email;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailOutboxRepository extends JpaRepository<EmailMessage, Long> {

    /** Locks the oldest due PENDING message; rows locked by another instance are skipped, so each message is sent once. */
    @Query(value = "select * from email_outbox where status = 'PENDING' and next_attempt_at <= :now "
            + "order by next_attempt_at, id limit 1 for update skip locked", nativeQuery = true)
    Optional<EmailMessage> lockNextDue(@Param("now") Instant now);

    @Query(value = "select * from email_outbox where status = 'PENDING' order by id for update skip locked", nativeQuery = true)
    List<EmailMessage> lockAllPending();

    long countByStatus(EmailMessage.Status status);

    long countByStatusAndUpdatedAtAfter(EmailMessage.Status status, Instant since);

    Optional<EmailMessage> findFirstByStatusOrderBySentAtDesc(EmailMessage.Status status);

    Page<EmailMessage> findByStatusOrderByCreatedAtDesc(EmailMessage.Status status, Pageable pageable);

    Page<EmailMessage> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<EmailMessage> findByToAddressIgnoreCaseOrderByIdAsc(String toAddress);

    List<EmailMessage> findByCategoryOrderByIdDesc(String category);

    @Modifying
    @Query("delete from EmailMessage m where m.status <> com.nexus360.email.EmailMessage.Status.PENDING and m.createdAt < :cutoff")
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff);
}
