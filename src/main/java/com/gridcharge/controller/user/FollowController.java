package com.gridcharge.controller.user;

import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.service.*;
import com.gridcharge.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/follows")
@RequiredArgsConstructor
@Validated
@Tag(name = "关注与站点动态")
public class FollowController {
    private final FollowService follows;

    @PutMapping("/{stationId}")
    @Operation(summary = "关注站点")
    public Result<Void> follow(@PathVariable @Positive Long stationId) {
        return null;
    }

    @DeleteMapping("/{stationId}")
    @Operation(summary = "取消关注")
    public Result<Void> unfollow(@PathVariable @Positive Long stationId) {
        return null;
    }

    @GetMapping
    @Operation(summary = "已关注站点")
    public Result<List<Station>> list() {
        return null;
    }

    @GetMapping("/common/{userId}")
    @Operation(summary = "与另一用户共同关注的站点")
    public Result<List<Station>> common(@PathVariable @Positive Long userId) {
        return null;
    }

    @GetMapping("/feed")
    @Operation(summary = "站点动态滚动分页，后续使用 nextMax 和 nextOffset")
    public Result<FeedVO> feed(
            @RequestParam(defaultValue = "9223372036854775807") @Min(0) long max,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int offset,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return null;
    }
}
