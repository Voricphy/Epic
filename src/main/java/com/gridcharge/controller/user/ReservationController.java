package com.gridcharge.controller.user;

import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.service.*;
import com.gridcharge.utils.UserHolder;
import com.gridcharge.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/reservations")
@RequiredArgsConstructor
@Validated
@Tag(name = "充电预约")
public class ReservationController {
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReservationSubmitFacade reservationSubmitFacade;

    @PostMapping
    @Operation(summary = "RocketMQ 异步预约，使用 requestId 幂等重试")
    public Result<Reservation> submit(@Valid @RequestBody ReservationDTO dto) {
        Long userId = UserHolder.getUser().getId();
        Reservation reservation = reservationSubmitFacade.submit(userId, dto, false);
        return Result.success(reservation);
    }

    @PostMapping("/sync")
    @Operation(summary = "MySQL 同步预约基线，与异步模式共用约束和状态机")
    public Result<Reservation> sync(@Valid @RequestBody ReservationDTO reservationDTO) {
        Long userId = UserHolder.getUser().getId();
        Reservation reservation = reservationSubmitFacade.submit(userId, reservationDTO, true);
        return Result.success(reservation);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查看自己的预约状态")
    public Result<Reservation> get(@PathVariable @Positive Long id) {
        Long userId = UserHolder.getUser().getId();
        return Result.success(reservationService.get(userId, id));
    }

    @GetMapping
    @Operation(summary = "我的预约列表")
    public Result<PageResult<Reservation>> list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return null;
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消自己尚未开始的预约，支持重复取消")
    public Result<Reservation> cancel(@PathVariable @Positive Long id) {
        Long userId = UserHolder.getUser().getId();
        return Result.success(reservationService.cancel(userId, id));
    }
}
