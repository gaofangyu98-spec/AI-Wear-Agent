package com.bite.aiwear.service;

import com.bite.aiwear.dto.request.EditImageRequest;
import com.bite.aiwear.dto.request.MergeImageRequest;
import com.bite.aiwear.dto.response.EditImageResponse;
import com.bite.aiwear.dto.response.MergeImageResponse;
import com.bite.aiwear.entity.Record;

import java.util.List;

// 历史记录服务接口
public interface RecordService {

    // 查询当前用户的调用记录
    List<Record> my(Long userId, String action);

    // 编辑生成调用记录
    void editSave(Long userId, EditImageRequest editImageRequest, EditImageResponse editImageResponse);

    // 合并生成调用记录
    void mergeSave(Long userId, MergeImageRequest mergeImageRequest, MergeImageResponse mergeImageResponse);
}
