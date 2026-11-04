package com.gridcharge.service;

import com.gridcharge.entity.*;
import com.gridcharge.vo.FeedVO;
import java.util.List;

public interface FollowService {
    void follow(Long userId, Long stationId, boolean enabled);

    List<Station> list(Long userId);

    List<Station> common(Long userId, Long otherId);

    void publish(StationSlot slot);

    FeedVO feed(Long userId, long max, int offset, int size);
}
