package com.bite.aiwear.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

// 图片表实体类（files）
@Data
@TableName("files")
public class ImageFile implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 上传用户ID
    @TableField("user_id")
    private Long userId;

    // 图片文件名
    @TableField("file_name")
    private String fileName;

    // 图片大小（字节）
    @TableField("file_size")
    private Long fileSize;

    // OSS访问地址
    @TableField("oss_url")
    private String ossUrl;
}
