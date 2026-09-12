package com.culture.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;

@Data
public class AjaxResult implements Serializable {

    private Boolean isSuccess = true;
    private String msg = "操作成功";

    //在json序列化时将java bean中的一些属性忽略掉，序列化和反序列化都受影响
    @JsonIgnore
    private Object data = new Object();

    public AjaxResult(String ms) {
        this.isSuccess = false;
        this.msg = ms;
    }

    public AjaxResult() {

    }
}
