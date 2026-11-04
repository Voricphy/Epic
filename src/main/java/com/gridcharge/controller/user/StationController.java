package com.gridcharge.controller.user;

import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.service.*;
import com.gridcharge.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/stations")
@Validated
@Tag(name = "充电站查询")
public class StationController {
    @Autowired
    private StationService stationService;

    @GetMapping
    @Operation(summary = "充电站分页查询")
    public Result<PageResult<Station>> page(
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String name) {
        return null;
    }

    @GetMapping("/{id}")
    @Operation(summary = "站点详情查询")
    public Result<Station> detail(@PathVariable @Positive Long id) {
        Station detail = stationService.detail(id);
        return Result.success(detail);
    }

    @GetMapping("/nearby")
    @Operation(summary = "附近站点，距离单位 km")
    public Result<List<NearbyStationVO>> nearby(
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double longitude,
            @RequestParam @DecimalMin("-85") @DecimalMax("85") double latitude,
            @RequestParam(defaultValue = "10") @DecimalMin("0.1") @DecimalMax("100") double radius,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return null;
    }

    @GetMapping("/{id}/slots")
    @Operation(summary = "未来预约时段及数据库已成功名额")
    public Result<List<StationSlot>> slots(@PathVariable @Positive Long id) {
        return null;
    }
}
