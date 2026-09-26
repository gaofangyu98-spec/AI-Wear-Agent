package com.bite.aiwear.commom;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结果包装类（注意包名为 commom，历史拼写，保持不变）。
 * 通过静态工厂方法返回带 code/message/data 的统一结构：200 成功、400 客户端错误、500 服务端错误。
 */
@Data
public class Result <T> implements Serializable {

    private Integer code;

    private String message;

    private T data;

    // 成功响应
    public static <T> Result<T> success(String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    // 客户端失败响应
    public static <T> Result<T> clientError(String message) {
        Result<T> result = new Result<>();
        result.setCode(400);
        result.setMessage(message);
        return result;
    }

    // 服务端失败响应
    public static <T> Result<T> serverError(String message) {
        Result<T> result = new Result<>();
        result.setCode(500);
        result.setMessage(message);
        return result;
    }

}
