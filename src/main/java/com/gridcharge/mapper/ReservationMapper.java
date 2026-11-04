package com.gridcharge.mapper;

import com.gridcharge.entity.*;
import com.gridcharge.vo.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ReservationMapper {

    Reservation findById(Long id);

    Reservation lockById(Long id);

    Reservation findRequest(@Param("userId") Long userId, @Param("requestId") String requestId);

    Reservation activeForUser(@Param("userId") Long userId, @Param("slotId") Long slotId);

    void insert(Reservation reservation);

    int transition(
            @Param("id") Long id,
            @Param("from") String from,
            @Param("to") String to,
            @Param("reason") String reason);

    List<Reservation> active(Long slotId);

    int cancelActiveBySlot(Long slotId);

    List<Reservation> list(
            @Param("userId") Long userId, @Param("offset") int offset, @Param("size") int size);

    long count(Long userId);

    List<RankingVO> ranking(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
