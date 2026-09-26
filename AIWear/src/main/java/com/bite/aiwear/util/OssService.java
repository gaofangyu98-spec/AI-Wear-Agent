package com.bite.aiwear.util;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * 阿里云 OSS 服务类。
 * 封装文件上传与访问 URL 拼装；配置来自 application 中的 aliyun.oss.* 项。
 */
@Service
@Slf4j
public class OssService {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    @Value("${aliyun.oss.max-size}")
    private Long maxSize;

    /** 允许上传的最大文件大小（字节），由配置注入。 */
    public Long getMaxSize() {
        return maxSize;
    }

    /**
     * 上传本地文件到 OSS，并返回可访问的 URL。
     * 每次新建 OSS 客户端并在 finally 中 shutdown，避免连接泄漏。
     */
    public String upload(String objectKey, File localFile, String contentType) {
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(localFile.length());
            if (contentType != null && contentType.startsWith("image/")) {
                metadata.setContentType(contentType);
            }

            ossClient.putObject(bucketName, objectKey, localFile, metadata);
            return buildUrl(objectKey);
        } finally {
            ossClient.shutdown();
        }
    }

    /**
     * 按 objectKey 拼装 OSS 公网访问地址。
     */
    private String buildUrl(String objectKey) {
        // OSS访问地址： https://{bucket}.{endpoint}/{objectKey}
        return "https://" + bucketName + "." + endpoint + "/" + objectKey;
    }
}
