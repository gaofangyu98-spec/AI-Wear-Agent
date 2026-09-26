package com.bite.aiwear.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.bite.aiwear.dto.request.EditImageRequest;
import com.bite.aiwear.dto.request.MergeImageRequest;
import com.bite.aiwear.dto.request.SearchImageRequest;
import com.bite.aiwear.dto.response.EditImageResponse;
import com.bite.aiwear.dto.response.MergeImageResponse;
import com.bite.aiwear.dto.response.SearchImageResponse;
import com.bite.aiwear.dto.response.UploadImageResponse;
import com.bite.aiwear.entity.ImageFile;
import com.bite.aiwear.mapper.ImageFileMapper;
import com.bite.aiwear.service.FileService;
import com.bite.aiwear.service.PythonImageService;
import com.bite.aiwear.service.RecordService;
import com.bite.aiwear.util.JwtUtil;
import com.bite.aiwear.util.OssService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 文件服务实现类。
 * 负责图片上传（本地暂存 -> Python 安全审核 -> OSS 同步 -> MySQL 落库 -> 向量化）、
 * 我的图片列表查询、以图搜文/文搜图、图片编辑与合并等核心业务流程。
 */
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    @Autowired
    private ImageFileMapper imageFileMapper;

    @Autowired
    private OssService ossService;

    @Autowired
    private PythonImageService pythonImageService;

    @Value("${file.upload.dir:uploads}")
    private String uploadBaseDir;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RecordService recordService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    /**
     * 上传图片：校验 -> 本地暂存 -> Python 审核 -> 上传 OSS -> 落库 files 表 -> 向量化。
     * 整个流程在事务内执行，finally 中清理本地临时文件。
     * @param file 客户端上传的图片文件
     * @param authorization 带 Bearer 的请求头令牌
     * @return 上传成功后的图片访问信息
     */
    public UploadImageResponse uploadImage(MultipartFile file, String authorization) {
        if (file == null) {
            throw new RuntimeException("缺少上传文件");
        }
        if (file.isEmpty()) {
            throw new RuntimeException("上传文件为空");
        }
        if (file.getContentType() == null || !file.getContentType().startsWith("image/")) {
            throw new RuntimeException("仅支持图片上传");
        }
        long fileSize = file.getSize();
        if (fileSize > ossService.getMaxSize()) {
            throw new RuntimeException("图片大小超过限制");
        }

        String token = jwtUtil.parseToken(authorization);
        Long userId = jwtUtil.getUserId(token);

        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.isBlank()) {
            originalFileName = "image";
        }

        String extension = getFileExtension(originalFileName, file.getContentType());
        String uuid = UUID.randomUUID().toString().replace("-", "");

        // 本地“系统”临时存储路径：uploads/images/{uuid}.ext
        Path localDir = Paths.get(uploadBaseDir, "images");
        try {
            Files.createDirectories(localDir);
        } catch (Exception e) {
            throw new RuntimeException("创建本地存储目录失败");
        }

        String finalLocalFileName = uuid + extension;
        Path localFilePath = localDir.resolve(finalLocalFileName);

        try {
            // 1) 保存到本地临时路径
            file.transferTo(localFilePath);

            // 2) 调用 Python 安全审核
            if (!pythonImageService.validateImage(localFilePath)) {
                throw new RuntimeException("图片审核不通过！");
            }

            // 3) 同步上传到 OSS
            String objectKey = "images/" + finalLocalFileName;
            File localFile = localFilePath.toFile();
            String url = ossService.upload(objectKey, localFile, file.getContentType());

            // 4) 落库 MySQL（优先保证图片元数据持久化）
            ImageFile record = new ImageFile();
            record.setUserId(userId);
            record.setFileName(originalFileName);
            record.setFileSize(fileSize);
            record.setOssUrl(url);
            imageFileMapper.insert(record);

            // 5) 调用 Python 服务生成向量并存入 Redis（实现后续向量搜索的关键）
            try {
                log.info("开始同步图片向量至 Python/Redis, userId: {}, url: {}", userId, url);
                pythonImageService.uploadImage(userId, url);
                log.info("同步图片向量至 Python/Redis 成功！");
            } catch (Exception e) {
                // 向量化失败不影响 MySQL 主流程，记录 warn 日志
                log.warn("调用 Python 图像向量化服务失败，但不影响图片保存, url: {}, error: {}", url, e.getMessage());
            }

            // 6) 组装响应体
            UploadImageResponse response = new UploadImageResponse();
            response.setUrl(url);
            // 响应仅回填 url / fileName / fileSize 三个字段
            response.setFileName(originalFileName);
            response.setFileSize(fileSize);

            return response;

        } catch (Exception e) {
            log.error("图片上传失败，原因：", e);
            throw new RuntimeException(e.getMessage());
        } finally {
            // 7) 清理本地临时文件，防止磁盘溢出
            try {
                Files.deleteIfExists(localFilePath);
            } catch (Exception e) {
                log.warn("清理本地临时文件失败: {}", localFilePath);
            }
        }
    }

    @Override
    /**
     * 查询当前登录用户上传的全部图片，按主键倒序返回。
     * @param authorization Bearer 令牌请求头
     * @return 当前用户的图片元数据列表
     */
    public List<ImageFile> myImages(String authorization) {
        String token = jwtUtil.parseToken(authorization);
        Long userId = jwtUtil.getUserId(token);
        LambdaQueryWrapper<ImageFile> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ImageFile::getUserId, userId).orderByDesc(ImageFile::getId);
        return imageFileMapper.selectList(queryWrapper);
    }

    @Override
    /**
     * 图片搜索：先取当前用户已上传图片建立 ossUrl->fileName 映射，
     * 再调用 Python 向量搜索接口，最后把结果中的 filePath 回填为可读文件名。
     * @param authorization Bearer 令牌请求头
     * @param searchImageRequest 搜索条件（文本 query 或上传的示例图片）
     * @return 命中的图片列表；调用异常或无结果时返回空列表
     */
    public List<SearchImageResponse> search(String authorization, SearchImageRequest searchImageRequest) {
        // 1. 获取用户 ID 及已上传的图片列表
        String token = jwtUtil.parseToken(authorization);
        Long userId = jwtUtil.getUserId(token);

        List<ImageFile> imageFiles = myImages(authorization);
        Map<String, String> map = new HashMap<>();
        if (imageFiles != null) {
            for (ImageFile imageFile : imageFiles) {
                if (imageFile != null && imageFile.getOssUrl() != null) {
                    map.put(imageFile.getOssUrl(), imageFile.getFileName());
                }
            }
        }

        // 2. 调用 Python 向量搜索服务
        List<SearchImageResponse> searchImageResponseList;
        try {
            searchImageResponseList = pythonImageService.search(userId, searchImageRequest);
        } catch (Exception e) {
            log.error("调用 Python 图像搜索服务失败: ", e);
            return Collections.emptyList(); // 接口调用异常时防崩溃，返回空列表
        }

        // 3. 空值拦截：如果 Python 返回 null 或空列表，直接返回空列表
        if (searchImageResponseList == null || searchImageResponseList.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. 组装文件名
        for (SearchImageResponse searchImageResponse : searchImageResponseList) {
            if (searchImageResponse != null && searchImageResponse.getFilePath() != null) {
                String fileName = map.getOrDefault(searchImageResponse.getFilePath(), "未知文件");
                searchImageResponse.setFileName(fileName);
            }
        }

        return searchImageResponseList;
    }

    @Override
    /**
     * 编辑图片：先校验目标图片必须是当前用户自己上传的，再调用 Python 编辑接口，
     * 成功后写入一条编辑调用记录。
     */
    public EditImageResponse edit(String authorization, EditImageRequest editImageRequest) {
        // 1. 鉴权 -> 用户只能编辑自己上传的图片
        List<ImageFile> imageFiles = myImages(authorization);
        // 2. 判断当前图片地址是否包含在imageFiles的ossUrl字段集合中
        List<String> urls = imageFiles.stream().map(ImageFile::getOssUrl).toList();
        if (!urls.contains(editImageRequest.getImage())) {
            throw new RuntimeException("只能编辑自己上传的图片");
        }
        // 3. 调用python服务，封装最后的返回结果
        EditImageResponse editImageResponse = pythonImageService.edit(editImageRequest);

        // 新增调用记录
        Long userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));
        recordService.editSave(userId, editImageRequest, editImageResponse);
        return editImageResponse;
    }

    @Override
    /**
     * 合并图片：先校验两张源图片都属于当前用户，再调用 Python 合并接口，
     * 成功后写入一条合并调用记录。
     */
    public MergeImageResponse merge(String authorization, MergeImageRequest mergeImageRequest) {
        // 1. 鉴权 -> 用户只能合并自己上传的图片
        List<ImageFile> imageFiles = myImages(authorization);
        // 2. 判断上传的图片是否包含在imageFiles的ossUrl字段集合中
        List<String> urls = imageFiles.stream().map(ImageFile::getOssUrl).toList();
        if (!urls.contains(mergeImageRequest.getImage1()) || !urls.contains(mergeImageRequest.getImage2())) {
            throw new RuntimeException("只能合并自己上传的图片");
        }
        // 3. 调用python服务
        MergeImageResponse mergeImageResponse = pythonImageService.merge(mergeImageRequest);
        // 新增调用记录
        Long userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));
        recordService.mergeSave(userId, mergeImageRequest, mergeImageResponse);
        return mergeImageResponse;
    }

    /**
     * 推断图片扩展名：优先用原始文件名后缀（超长则丢弃），
     * 否则依据 contentType 映射，兜底返回 .png。
     */
    private String getFileExtension(String originalFileName, String contentType) {
        if (originalFileName != null && originalFileName.contains(".")) {
            String ext = originalFileName.substring(originalFileName.lastIndexOf(".")).toLowerCase(Locale.ROOT);
            if (ext.length() <= 10) {
                return ext;
            }
        }
        if (contentType == null) {
            return ".png";
        }
        if (contentType.equalsIgnoreCase("image/png")) {
            return ".png";
        }
        if (contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/jpg")) {
            return ".jpg";
        }
        if (contentType.equalsIgnoreCase("image/gif")) {
            return ".gif";
        }
        if (contentType.equalsIgnoreCase("image/webp")) {
            return ".webp";
        }
        return ".png";
    }
}
