package com.bite.aiwear.dto.response;

import lombok.Data;

// 合并图片的响应实体类
@Data
public class MergeImageResponse {

    // python服务返回的地址
    private String url;

    // 保存到oss的地址
    private String saveUrl;
}
