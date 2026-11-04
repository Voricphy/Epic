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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/activity")
@RequiredArgsConstructor
@Tag(name = "用户活跃")
public class ActivityController {
    private final StatisticsService statistics;

    @GetMapping("/streak")
    @Operation(summary = "截至今天连续活跃天数，最多返回366天")
    public Result<Integer> streak() {
        return null;
    }
}
