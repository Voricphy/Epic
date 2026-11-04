package com.gridcharge.mapper;

import com.gridcharge.entity.AdminCancellationMail;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AdminCancellationMailMapper {
    int enqueueActive(Long slotId);

    List<Long> dueIds();

    AdminCancellationMail findDue(Long reservationId);

    int markSent(Long reservationId);

    int retryLater(@Param("reservationId") Long reservationId,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                   @Param("error") String error);
}
