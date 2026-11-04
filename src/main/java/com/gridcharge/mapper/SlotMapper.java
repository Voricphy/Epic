package com.gridcharge.mapper;

import com.gridcharge.entity.*;
import com.gridcharge.vo.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface SlotMapper {

    StationSlot findById(Long id);

    StationSlot lockById(Long id);

    List<StationSlot> list(Long stationId);

    List<Long> recoveryIds(@Param("after") long after);

    int overlaps(
            @Param("stationId") Long stationId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    void insert(StationSlot slot);

    int reserve(Long id);

    int release(Long id);

    void close(Long id);

    int open(Long id);

    void clearReserved(Long id);

    int capacityViolations(
            @Param("stationId") Long stationId,
            @Param("pileCount") int pileCount,
            @Param("maxPowerKw") java.math.BigDecimal maxPowerKw);
}
