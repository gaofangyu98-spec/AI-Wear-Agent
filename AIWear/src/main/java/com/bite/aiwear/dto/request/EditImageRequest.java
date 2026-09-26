package com.bite.aiwear.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// 图片编辑的请求实体类
@Data
public class EditImageRequest {

    // 当前图片上传了之后产生的oss地址
    @NotBlank(message = "图片不能为空")
    private String image;

    // 编辑指令
    @NotBlank(message = "编辑图片的指令不能为空")
    private String instruction;

}
