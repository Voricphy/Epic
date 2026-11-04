package com.gridcharge.service;

import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.dto.PageResult;
import com.gridcharge.vo.NearbyStationVO;
import java.util.List;

public interface StationService {
    Station detail(Long id);

    Station adminDetail(Long id);

    PageResult<Station> page(int page, int size, String name);

    Long save(Long id, StationDTO dto);

    void delete(Long id);

    List<NearbyStationVO> nearby(double longitude, double latitude, double radius, int limit);

    List<StationSlot> slots(Long stationId);

    Long publish(Long stationId, SlotDTO dto);

    void closeSlot(Long slotId);

    void openSlot(Long slotId);

    int cancelSlot(Long slotId);

    void rebuildGeo();
}
