package com.gridcharge.service;

import com.gridcharge.vo.RankingVO;
import java.time.LocalDate;
import java.util.List;

public interface StatisticsService {
    void markActive(Long userId);

    int streak(Long userId);

    void visit(Long stationId, Long userId);

    long uv(Long stationId, LocalDate date);

    List<RankingVO> ranking(LocalDate date, int size);
}
