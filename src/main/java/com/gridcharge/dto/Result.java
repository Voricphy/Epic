package com.gridcharge.dto;

import java.io.Serializable;
import lombok.Data;

@Data
public class Result<T> implements Serializable {
    private int code;
    private String msg;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 1;
        r.data = data;
        return r;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(String msg) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.msg = msg;
        return r;
    }
}
