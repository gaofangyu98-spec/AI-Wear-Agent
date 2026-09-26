package com.bite.aiwear.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// 合并图片的请求实体类
@Data
public class MergeImageRequest {

    @NotBlank(message = "图片1不能为空")
    private String image1;

    @NotBlank(message = "图片2不能为空")
    private String image2;

    @NotBlank(message = "合并指令不能为空")
    private String instruction;

}
