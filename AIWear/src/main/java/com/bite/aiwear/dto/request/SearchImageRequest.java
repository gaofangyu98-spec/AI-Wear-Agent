package com.bite.aiwear.dto.request;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

// 搜索图片的DTO
@Data
public class SearchImageRequest {

    private String query;

    private MultipartFile file;

}
