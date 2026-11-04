package com.gridcharge.service.impl;

import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.mapper.*;
import com.gridcharge.service.*;
import com.gridcharge.vo.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 学习骨架：保留 MySQL 基础操作，TODO 方法由学习者补充。 */
@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    @Override
    public void markActive(Long userId) {
        // TODO 学习任务：自行完成 StatisticsService.markActive。
        throw new UnsupportedOperationException("待实现：StatisticsService.markActive");
    }

    @Override
    public int streak(Long userId) {
        // TODO 学习任务：自行完成 StatisticsService.streak。
        throw new UnsupportedOperationException("待实现：StatisticsService.streak");
    }

    @Override
    public void visit(Long stationId, Long userId) {
        // TODO 学习任务：自行完成 StatisticsService.visit。
        throw new UnsupportedOperationException("待实现：StatisticsService.visit");
    }

    @Override
    public long uv(Long stationId, LocalDate date) {
        // TODO 学习任务：自行完成 StatisticsService.uv。
        throw new UnsupportedOperationException("待实现：StatisticsService.uv");
    }

    @Override
    public List<RankingVO> ranking(LocalDate date, int size) {
        // TODO 学习任务：自行完成 StatisticsService.ranking。
        throw new UnsupportedOperationException("待实现：StatisticsService.ranking");
    }
}
