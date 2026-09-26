package com.bite.aiwear.service.impl;

import com.bite.aiwear.dto.request.EditImageRequest;
import com.bite.aiwear.dto.request.MergeImageRequest;
import com.bite.aiwear.dto.request.PythonUploadImageRequest;
import com.bite.aiwear.dto.request.SearchImageRequest;
import com.bite.aiwear.dto.response.EditImageResponse;
import com.bite.aiwear.dto.response.MergeImageResponse;
import com.bite.aiwear.dto.response.PythonUploadImageResponse;
import com.bite.aiwear.dto.response.SearchImageResponse;
import com.bite.aiwear.service.PythonImageService;
import com.bite.aiwear.util.OssService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
/**
 * Python 图像服务调用实现类。
 * 通过 HTTP 与外部 Python 服务交互，提供：图片向量化上传、图片安全审核、
 * 向量搜索、图片编辑与合并；编辑/合并结果会先下载到本地临时文件再回传 OSS。
 */
@Slf4j
@Service
public class PythonImageServiceImpl implements PythonImageService {

    @Value("${python.service.base-url:http://localhost:5000}")
    private String pythonBaseUrl;

    // 这里使用 Java 内置 HttpClient，避免额外依赖
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    private OssService ossService;

    @Autowired
    public PythonImageServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    /**
     * 把图片的 OSS 地址与 userId 发送给 Python /api/upload-image，
     * 由 Python 侧生成向量并写入 Redis 向量库。
     */
    public void uploadImage(Long userId, String ossUrl) {
        if (userId == null) {
            throw new RuntimeException("缺少 userId");
        }
        if (ossUrl == null || ossUrl.isBlank()) {
            throw new RuntimeException("缺少 ossUrl");
        }

        PythonUploadImageRequest requestBody = new PythonUploadImageRequest();
        requestBody.setUserId(userId);
        requestBody.setOssUrl(ossUrl);

        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("构造 Python 请求体失败");
        }

        String url = pythonBaseUrl + "/api/upload-image";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        } catch (Exception e) {
            log.error("调用 Python /api/upload-image 失败：{}", e.getMessage());
            throw new RuntimeException("调用 Python 服务失败");
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("Python 服务返回非 2xx：status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Python 服务返回错误");
        }

        try {
            PythonUploadImageResponse pythonResponse =
                    objectMapper.readValue(response.body(), PythonUploadImageResponse.class);
            if (pythonResponse.getSuccess() == null || !pythonResponse.getSuccess()) {
                throw new RuntimeException("Python 上传失败");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 Python 响应失败：{}", e.getMessage());
            throw new RuntimeException("解析 Python 响应失败");
        }
    }

    @Override
    /**
     * 调用 Python /api/validate-image 对本地图片做内容安全审核。
     * 任何异常（含网络/解析失败）都按不通过处理，避免风险图片流入。
     * @return true 表示审核放行
     */
    public boolean validateImage(Path filePath) {
        try {
            String url = pythonBaseUrl + "/api/validate-image";
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(filePath));
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, httpHeaders);
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
            return objectMapper.readTree(response.getBody()).path("allow").asBoolean(false);
        } catch (Exception e) {
            return false;
        }

    }

    @Override
    /**
     * 调用 Python /api/search-image 做向量搜索，支持文本 query 与示例图片两种入参。
     * 异常时返回空列表，不向上抛出。
     */
    public List<SearchImageResponse> search(Long userId, SearchImageRequest searchImageRequest) {
        String url = pythonBaseUrl + "/api/search-image";
        String query = searchImageRequest.getQuery();
        MultipartFile file = searchImageRequest.getFile();

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("userId", userId);
            if (query != null && !query.isBlank()) {
                body.add("query", query);
            }

            if (file != null) {
                byte[] fileBytes = file.getBytes();
                String filename = file.getOriginalFilename();
                ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };
                body.add("file", fileResource);
            }

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, httpHeaders);
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
            JsonNode result = objectMapper.readTree(response.getBody())
                    .path("data");
            List<SearchImageResponse> searchImageResponseList = new ArrayList<>();
            for (JsonNode node : result) {
                SearchImageResponse searchImageResponse = new SearchImageResponse();
                searchImageResponse.setFilePath(node.path("filePath").asText(""));
                searchImageResponseList.add(searchImageResponse);
            }
            return searchImageResponseList;

        } catch (Exception e) {
            log.error("调用python服务的搜索图片接口失败{}", e.getMessage());
            return List.of();
        }
    }

    @Override
    /**
     * 编辑图片：把 OSS 源图下载到临时文件 -> 调 Python 编辑接口 ->
     * 把返回结果图再下载并转存 OSS。finally 中删除所有临时文件。
     */
    public EditImageResponse edit(EditImageRequest editImageRequest) {
        // 1. 考虑转换  oss url -> file资源
        String sourceImageUrl = editImageRequest.getImage();
        String instruction = editImageRequest.getInstruction();
        Path sourceTemp = null;
        Path editTemp = null;

        sourceTemp = downloadToTempFile(sourceImageUrl);
        // 2. 构造请求参数去访问python服务的编辑图片接口
        String url = pythonBaseUrl + "/api/skill/image";
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(sourceTemp.toFile()));
        body.add("instruction", instruction);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, httpHeaders);
        RestTemplate restTemplate = new RestTemplate();

        // 3. 拿到python服务返回的响应之后需要处理数据
        ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
        try {
            JsonNode result = objectMapper.readTree(response.getBody());
            String pythonUrl = result.path("url").asText("");
            // 4. 图片地址需要二次保存
            editTemp = downloadToTempFile(pythonUrl);
            String contentType = Files.probeContentType(editTemp);
            String extension = ".png";
            String objectKey = "image/edited/" + UUID.randomUUID().toString().replace("-", "") + extension;
            String saveUrl = ossService.upload(objectKey, editTemp.toFile(), contentType);
            // 5. 封装一个返回对象
            EditImageResponse editImageResponse = new EditImageResponse();
            editImageResponse.setUrl(pythonUrl);
            editImageResponse.setSaveUrl(saveUrl);
            return editImageResponse;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            // 6. 处理无论成功与否，都应该删掉临时文件（加防空指针保护）
            try {
                if (sourceTemp != null) Files.deleteIfExists(sourceTemp);
                if (editTemp != null) Files.deleteIfExists(editTemp);
            } catch (IOException e) {
                log.error("删除临时文件失败: {}", e.getMessage());
            }
        }
    }

    @Override
    /**
     * 合并图片：下载两张 OSS 源图到临时文件 -> 调 Python 合并接口 ->
     * 结果图再下载并转存 OSS。finally 中删除所有临时文件。
     */
    public MergeImageResponse merge(MergeImageRequest mergeImageRequest) {
        // 1. 文件转换   oss url   -> file 资源
        String source1ImageUrl = mergeImageRequest.getImage1();
        String source2ImageUrl = mergeImageRequest.getImage2();
        String instruction = mergeImageRequest.getInstruction();
        Path source1Temp = downloadToTempFile(source1ImageUrl);
        Path source2Temp = downloadToTempFile(source2ImageUrl);
        Path mergeTemp = null;
        // 2. 构造请求参数去访问python服务的合并图片接口
        String url = pythonBaseUrl + "/api/skill/image";
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file1", new FileSystemResource(source1Temp.toFile()));
        body.add("file2", new FileSystemResource(source2Temp.toFile()));
        body.add("instruction", instruction);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, httpHeaders);
        RestTemplate restTemplate = new RestTemplate();

        // 3. 拿到python服务返回的响应
        ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
        try {
            JsonNode result = objectMapper.readTree(response.getBody());
            String pythonUrl = result.path("url").asText("");
            // 4. 拿到的图片进行二次保存到oss
            mergeTemp = downloadToTempFile(pythonUrl);
            String contentType = Files.probeContentType(mergeTemp);
            String extension = ".png";
            String objectKey = "image/merged/" + UUID.randomUUID().toString().replace("-", "") + extension;
            String saveUrl = ossService.upload(objectKey, mergeTemp.toFile(), contentType);
            // 5. 封装一个返回的对象
            MergeImageResponse mergeImageResponse = new MergeImageResponse();
            mergeImageResponse.setUrl(pythonUrl);
            mergeImageResponse.setSaveUrl(saveUrl);
            return mergeImageResponse;

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            // 6. 删掉临时文件（加防空指针保护）
            try {
                if (source1Temp != null) Files.deleteIfExists(source1Temp);
                if (source2Temp != null) Files.deleteIfExists(source2Temp);
                if (mergeTemp != null) Files.deleteIfExists(mergeTemp);
            } catch (IOException e) {
                log.error("删除临时文件失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 把远程 URL 对应的图片流式下载到本地临时文件，供 Python 接口上传。
     * URL 为空或下载失败时抛出运行时异常。
     */
    private Path downloadToTempFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            log.error("尝试下载的文件的 URL 为空！");
            throw new RuntimeException("文件 URL 不能为空");
        }
        try {
            log.info("开始下载远程文件到临时文件: {}", fileUrl);
            URL url = URI.create(fileUrl).toURL();
            Path tempFile = Files.createTempFile("image-edit-", ".tmp");
            try (InputStream inputStream = url.openStream()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (Exception e) {
            log.error("下载远程文件失败，URL: {}, 原因: {}", fileUrl, e.getMessage(), e);
            throw new RuntimeException("下载远程文件失败: " + e.getMessage());
        }
    }
}
