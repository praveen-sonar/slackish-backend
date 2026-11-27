package com.slackish.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByChannelIdAndParentIdIsNullOrderByCreatedAtAsc(Long channelId);
    List<Message> findByChannelIdOrderByCreatedAtAsc(Long channelId, Pageable pageable);
    List<Message> findByChannelIdAndIdLessThanOrderByIdDesc(Long channelId, Long id, Pageable pageable);
    // body is a @Lob, so it is cast to a string for the case-insensitive match
    @Query("select m from Message m where m.channelId in :channelIds and m.deleted = false "
            + "and lower(cast(m.body as string)) like lower(concat('%', :q, '%')) and m.createdAt < :before "
            + "order by m.createdAt desc")
    List<Message> search(@Param("channelIds") Collection<Long> channelIds, @Param("q") String q,
                         @Param("before") Instant before, Pageable pageable);
    List<Message> findByChannelId(Long channelId);
    List<Message> findByParentIdOrderByCreatedAtAsc(Long parentId);
}
