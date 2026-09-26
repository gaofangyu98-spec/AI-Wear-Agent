package com.bite.aiwear.controller;

import com.bite.aiwear.commom.Result;
import com.bite.aiwear.dto.request.EditImageRequest;
import com.bite.aiwear.dto.request.MergeImageRequest;
import com.bite.aiwear.dto.request.SearchImageRequest;
import com.bite.aiwear.dto.response.EditImageResponse;
import com.bite.aiwear.dto.response.MergeImageResponse;
import com.bite.aiwear.dto.response.SearchImageResponse;
import com.bite.aiwear.dto.response.UploadImageResponse;
import com.bite.aiwear.entity.ImageFile;
import com.bite.aiwear.log.ApiLog;
import com.bite.aiwear.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件模块控制器。
 * 提供图片上传、我的图片列表、图片搜索、图片编辑与合并等 HTTP 接口，统一挂在 /api/file 下。
 */
@RestController
@Slf4j
@RequestMapping("/api/file")
public class FileController {

    @Autowired
    private FileService fileService;

    // 上传图片（系统本地存储 -> OSS同步 -> files表落库）
    @ApiLog
    @PostMapping("/upload/image")
    public Result<UploadImageResponse> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization") String authorization
    ) {
        UploadImageResponse response = fileService.uploadImage(file, authorization);
        return Result.success("图片上传成功", response);
    }

    // 我的图片列表（用来展示当前用户上传的图片）
    @ApiLog
    @GetMapping("/my-images")
    public Result<List<ImageFile>> myImages(
            @RequestHeader(value = "Authorization") String authorization
    ) {
        List<ImageFile> imageFiles = fileService.myImages(authorization);
        return Result.success("查询成功", imageFiles);
    }

    // 图片搜索（支持文搜图和图搜图）
    @ApiLog
    @PostMapping("/search")
    public Result<List<SearchImageResponse>> search(
            @RequestHeader(value = "Authorization") String authorization,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        SearchImageRequest searchImageRequest = new SearchImageRequest();
        searchImageRequest.setQuery(query);
        searchImageRequest.setFile(file);
        return Result.success("查询成功", fileService.search(authorization, searchImageRequest));
    }

    // 图片编辑
    @ApiLog
    @PostMapping("/edit")
    public Result<EditImageResponse> edit(
            @RequestBody @Validated EditImageRequest editImageRequest,
            @RequestHeader(value = "Authorization") String authorization
    ) {
        return Result.success("编辑成功", fileService.edit(authorization, editImageRequest));
    }

    // 图片合并
    @ApiLog
    @PostMapping("/merge")
    public Result<MergeImageResponse> merge(
            @RequestBody @Validated MergeImageRequest mergeImageRequest,
            @RequestHeader(value = "Authorization") String authorization
    ) {
        return Result.success("合并成功", fileService.merge(authorization, mergeImageRequest));
    }
}
