package com.bite.aiwear.dto.response;

import lombok.Data;

// 图片编辑的响应实体类
@Data
public class EditImageResponse {

    // 经过python服务返回的url地址
    private String url;

    // 需要保存的oss地址
    private String saveUrl;

}
