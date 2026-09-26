package com.bite.aiwear.dto.request;

import lombok.Data;

import java.io.Serializable;

// 调用 Python /api/upload-image 的请求体
@Data
public class PythonUploadImageRequest implements Serializable {
    private String ossUrl;
    private Long userId;
}
