package com.culture.util;

import lombok.Data;

import java.util.HashMap;
//使用封装思想
@Data
public class SucErrResult extends HashMap {

    //成功调用的方法
    public static SucErrResult ok(){
        return new SucErrResult();
    }

    //失败调用的方法
    public static SucErrResult error(String msg){
        return new SucErrResult(msg);
    }

    public SucErrResult put(String key, Object value){
        super.put(key,value);
        return this;
    }

    public SucErrResult(){
        put("isSuccess",true);
        put("message","操作成功");
    }

    public SucErrResult(String msg){
        put("isSuccess",false);
        put("message",msg);
    }

}
