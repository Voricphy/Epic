package com.gridcharge.controller.admin;

import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.service.*;
import com.gridcharge.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController("adminStationController")
@RequestMapping("/admin/stations")
@RequiredArgsConstructor
@Validated
@Tag(name = "管理端：充电站及预约时段")
public class StationController {
    private final StationService stations;

    @GetMapping
    @Operation(summary = "管理端站点分页")
    public Result<PageResult<Station>> page(
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String name) {
        return Result.success(stations.page(page, size, name));
    }

    @GetMapping("/{id}")
    @Operation(summary = "管理端站点详情")
    public Result<Station> detail(@PathVariable @Positive Long id) {
        return Result.success(stations.adminDetail(id));
    }

    @PostMapping
    @Operation(summary = "创建站点")
    public Result<Long> create(@Valid @RequestBody StationDTO dto) {
        return Result.success(stations.save(null, dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改站点，包括启用/停用")
    public Result<Long> update(
            @PathVariable @Positive Long id, @Valid @RequestBody StationDTO dto) {
        return Result.success(stations.save(id, dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除没有时段的站点")
    public Result<Void> delete(@PathVariable @Positive Long id) {
        stations.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/slots")
    @Operation(summary = "发布新预约时段")
    public Result<Long> publish(@PathVariable @Positive Long id, @Valid @RequestBody SlotDTO dto) {
        return Result.success(stations.publish(id, dto));
    }

    @PostMapping("/slots/{slotId}/close")
    @Operation(summary = "关闭时段，不再接收新预约")
    public Result<Void> close(@PathVariable @Positive Long slotId) {
        stations.closeSlot(slotId);
        return Result.success();
    }

    @PostMapping("/slots/{slotId}/open")
    @Operation(summary = "重新开放尚未开始的时段，已取消的预约不恢复")
    public Result<Void> open(@PathVariable @Positive Long slotId) {
        stations.openSlot(slotId);
        return Result.success();
    }

    @PostMapping("/slots/{slotId}/cancel")
    @Operation(summary = "关闭时段并取消已有预约，返回本次取消的订单数")
    public Result<Integer> cancel(@PathVariable @Positive Long slotId) {
        return Result.success(stations.cancelSlot(slotId));
    }
}
