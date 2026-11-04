package com.gridcharge.mapper;

import com.gridcharge.entity.*;
import com.gridcharge.vo.*;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FollowMapper {

    void insert(@Param("userId") Long userId, @Param("stationId") Long stationId);

    void delete(@Param("userId") Long userId, @Param("stationId") Long stationId);

    List<Long> stations(Long userId);

    List<Long> followers(Long stationId);

    List<StationSlot> feed(Long userId);
}
