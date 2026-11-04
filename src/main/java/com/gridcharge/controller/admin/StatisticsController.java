package com.gridcharge.controller.admin;

import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.service.*;
import com.gridcharge.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/statistics")
@RequiredArgsConstructor
@Validated
@Tag(name = "管理端：运营统计")
public class StatisticsController {
    private final StatisticsService statistics;

    @GetMapping("/stations/{id}/uv")
    @Operation(summary = "站点每日 UV，HLL 近似统计，保留90天")
    public Result<Long> uv(@PathVariable @Positive Long id, @RequestParam LocalDate date) {
        return null;
    }

    @GetMapping("/ranking")
    @Operation(summary = "按订单创建日统计仍有效的成功预约排行，最多30秒延迟")
    public Result<List<RankingVO>> ranking(
            @RequestParam LocalDate date,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return null;
    }
}
