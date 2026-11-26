package com.slackish.dm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DmMessageRepository extends JpaRepository<DmMessage, Long> {
    List<DmMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
    List<DmMessage> findByConversationIdAndIdLessThanOrderByIdDesc(Long conversationId, Long id, Pageable pageable);
    // body is a @Lob, so it is cast to a string for the case-insensitive match
    @Query("select m from DmMessage m where m.conversationId in :conversationIds "
            + "and lower(cast(m.body as string)) like lower(concat('%', :q, '%')) and m.createdAt < :before "
            + "order by m.createdAt desc")
    List<DmMessage> search(@Param("conversationIds") Collection<Long> conversationIds, @Param("q") String q,
                           @Param("before") Instant before, Pageable pageable);
}
