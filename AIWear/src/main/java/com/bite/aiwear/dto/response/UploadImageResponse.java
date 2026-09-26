package com.bite.aiwear.dto.response;

import lombok.Data;

import java.io.Serializable;

// 上传图片响应实体类
@Data
public class UploadImageResponse implements Serializable {

    private String url;

    private String fileName;

    private Long fileSize;
}
