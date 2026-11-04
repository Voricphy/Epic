package com.gridcharge.service.impl;

import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.exception.BusinessException;
import com.gridcharge.mapper.*;
import com.gridcharge.service.*;
import com.gridcharge.vo.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 学习骨架：保留 MySQL 基础操作，TODO 方法由学习者补充。 */
@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {
    private final FollowMapper follows;
    private final StationMapper stations;

    @Transactional
    @Override
    public void follow(Long userId, Long stationId, boolean enabled) {
        if (stations.lockById(stationId) == null) throw new BusinessException(404, "充电站不存在");
        if (enabled) follows.insert(userId, stationId);
        else follows.delete(userId, stationId);
        // TODO 学习任务：数据库关注关系与 Redis Set 的维护。
    }

    @Override
    public List<Station> list(Long userId) {
        return follows.stations(userId).stream()
                .map(stations::findById)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<Station> common(Long userId, Long otherId) {
        // TODO 学习任务：自行完成 FollowService.common。
        throw new UnsupportedOperationException("待实现：FollowService.common");
    }

    @Override
    public void publish(StationSlot slot) {
        // TODO 学习任务：自行完成 FollowService.publish。
        throw new UnsupportedOperationException("待实现：FollowService.publish");
    }

    @Override
    public FeedVO feed(Long userId, long max, int offset, int size) {
        // TODO 学习任务：自行完成 FollowService.feed。
        throw new UnsupportedOperationException("待实现：FollowService.feed");
    }
}
