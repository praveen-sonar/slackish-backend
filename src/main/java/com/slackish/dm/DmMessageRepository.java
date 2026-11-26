package com.slackish.dm;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface DmMessageRepository extends JpaRepository<DmMessage, Long> {
    List<DmMessage> findByConversationIdAndIdLessThanOrderByIdDesc(Long conversationId, Long before, Pageable pageable);

    // body is a @Lob, which Hibernate won't pass to lower()/upper() without a cast, so this can't be a derived query.
    @Query("""
            select m from DmMessage m
            where m.conversationId in :conversationIds and m.id < :before
              and lower(cast(m.body as String)) like :pattern escape '\\'
            order by m.id desc""")
    List<DmMessage> search(@Param("conversationIds") Collection<Long> conversationIds, @Param("before") Long before,
                           @Param("pattern") String pattern, Pageable pageable);
}
