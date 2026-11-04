package com.gridcharge.utils;

import com.gridcharge.entity.Station;
import com.gridcharge.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CapacityPolicy {
    private CapacityPolicy() {}

    public static int capacity(Station station, BigDecimal power) {
        if (power == null || power.signum() <= 0) throw new BusinessException(400, "单车预留功率必须为正数");
        return station.getMaxPowerKw()
                .divide(power, 0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(station.getPileCount()))
                .intValueExact();
    }

    public static void validate(Station station, BigDecimal power, int quota) {
        if (quota < 1 || quota > capacity(station, power))
            throw new BusinessException(400, "名额超过充电桩数量或站点可分配功率限制");
    }
}
