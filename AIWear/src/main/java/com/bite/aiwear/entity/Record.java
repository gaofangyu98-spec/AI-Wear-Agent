package com.bite.aiwear.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

// 历史记录的数据表对应的实体类
@Data
@TableName("records")
public class Record implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String action;

    @TableField("input_oss_url1")
    private String inputOssUrl1;

    @TableField("input_oss_url2")
    private String inputOssUrl2;

    private String instruction;

    @TableField("result_url")
    private String resultUrl;

    @TableField("output_oss_url")
    private String outputOssUrl;

}
