package com.culture.query;

import lombok.Data;

@Data
public class UserQuery   extends BaseQuery{
    private String username;
    private String email;
}
