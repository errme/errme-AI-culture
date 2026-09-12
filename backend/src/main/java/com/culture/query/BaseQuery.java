package com.culture.query;

import lombok.Data;


@Data
public class BaseQuery {
    //开始位置
    private Integer offset = 0;
    //每页显示条数
    private Integer pageSize = 10;

    private Integer page;
}
