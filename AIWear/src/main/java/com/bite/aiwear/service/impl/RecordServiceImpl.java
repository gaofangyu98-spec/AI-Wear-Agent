package com.bite.aiwear.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bite.aiwear.dto.request.EditImageRequest;
import com.bite.aiwear.dto.request.MergeImageRequest;
import com.bite.aiwear.dto.response.EditImageResponse;
import com.bite.aiwear.dto.response.MergeImageResponse;
import com.bite.aiwear.mapper.RecordMapper;
import com.bite.aiwear.service.RecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.bite.aiwear.entity.Record;
import java.util.List;

/**
 * 历史调用记录服务实现类。
 * 负责记录图片编辑/合并的调用流水，并按用户、动作类型查询调用历史。
 */
@Service
public class RecordServiceImpl implements RecordService {

    @Autowired
    private RecordMapper recordMapper;

    @Override
    /**
     * 查询指定用户的调用记录，action 非空时按动作类型过滤，按主键倒序。
     */
    public List<Record> my(Long userId, String action) {
        LambdaQueryWrapper<Record> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Record::getUserId, userId);
        if (action != null && !action.isBlank()) {
            queryWrapper.eq(Record::getAction, action);
        }
        queryWrapper.orderByDesc(Record::getId);
        return recordMapper.selectList(queryWrapper);
    }

    @Override
    /**
     * 写入一条图片编辑调用记录。
     */
    public void editSave(Long userId, EditImageRequest editImageRequest, EditImageResponse editImageResponse) {
        Record record = new Record();
        record.setUserId(userId);
        record.setAction("edit");
        record.setInputOssUrl1(editImageRequest.getImage());
        record.setInstruction(editImageRequest.getInstruction());
        record.setResultUrl(editImageResponse.getUrl());
        record.setOutputOssUrl(editImageResponse.getSaveUrl());
        recordMapper.insert(record);
    }

    @Override
    /**
     * 写入一条图片合并调用记录。
     */
    public void mergeSave(Long userId, MergeImageRequest mergeImageRequest, MergeImageResponse mergeImageResponse) {
        Record record = new Record();
        record.setUserId(userId);
        record.setAction("merge");
        record.setInputOssUrl1(mergeImageRequest.getImage1());
        record.setInputOssUrl2(mergeImageRequest.getImage2());
        record.setInstruction(mergeImageRequest.getInstruction());
        record.setResultUrl(mergeImageResponse.getUrl());
        record.setOutputOssUrl(mergeImageResponse.getSaveUrl());
        recordMapper.insert(record);
    }
}
