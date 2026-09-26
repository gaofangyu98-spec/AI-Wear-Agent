package com.bite.aiwear.service;

import com.bite.aiwear.dto.request.EditImageRequest;
import com.bite.aiwear.dto.request.MergeImageRequest;
import com.bite.aiwear.dto.request.SearchImageRequest;
import com.bite.aiwear.dto.response.EditImageResponse;
import com.bite.aiwear.dto.response.MergeImageResponse;
import com.bite.aiwear.dto.response.SearchImageResponse;

import java.nio.file.Path;
import java.util.List;

// Python 图片向量化服务（后续可能会复用在搜索等功能）
public interface PythonImageService {

    // 上传图片后的 OSS URL 与 userId 发给 Python /api/upload-image
    void uploadImage(Long userId, String ossUrl);

    // 审核图片 （上传之前先添加审核）
    boolean validateImage(Path filePath);

    // 搜索图片
    List<SearchImageResponse> search(Long userId, SearchImageRequest searchImageRequest);

    // 编辑图片
    EditImageResponse edit(EditImageRequest editImageRequest);

    // 合并图片
    MergeImageResponse merge(MergeImageRequest mergeImageRequest);
}
