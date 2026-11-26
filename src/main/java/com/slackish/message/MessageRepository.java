package com.slackish.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByChannelIdAndParentIdIsNullOrderByCreatedAtAsc(Long channelId);
    List<Message> findByChannelIdAndIdLessThanOrderByIdDesc(Long channelId, Long before, Pageable pageable);
    List<Message> findByChannelId(Long channelId);
    List<Message> findByParentIdOrderByCreatedAtAsc(Long parentId);

    // body is a @Lob, which Hibernate won't pass to lower()/upper() without a cast, so this can't be a derived query.
    @Query("""
            select m from Message m
            where m.channelId in :channelIds and m.id < :before and m.deleted = false
              and lower(cast(m.body as String)) like :pattern escape '\\'
            order by m.id desc""")
    List<Message> search(@Param("channelIds") Collection<Long> channelIds, @Param("before") Long before,
                         @Param("pattern") String pattern, Pageable pageable);
}
