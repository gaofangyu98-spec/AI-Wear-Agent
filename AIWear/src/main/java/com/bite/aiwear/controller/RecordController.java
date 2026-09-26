package com.bite.aiwear.controller;

import com.bite.aiwear.commom.Result;
import com.bite.aiwear.entity.Record;
import com.bite.aiwear.log.ApiLog;
import com.bite.aiwear.service.RecordService;
import com.bite.aiwear.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 历史调用记录控制器。
 * 提供当前用户图片编辑/合并调用记录的查询接口，挂在 /api/record 下。
 */
@Slf4j
@RestController
@RequestMapping("/api/record")
public class RecordController {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RecordService recordService;

    // 查看调用记录
    @ApiLog
    @GetMapping("/my")
    public Result<List<Record>> my(
            @RequestHeader(value = "Authorization") String authorization,
            @RequestParam(value = "action", required = false) String action
    ) {
        String token = jwtUtil.parseToken(authorization);
        Long userId = jwtUtil.getUserId(token);
        return Result.success("查询成功", recordService.my(userId, action));
    }
}
