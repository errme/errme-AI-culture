package com.culture;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication(scanBasePackages = "com.culture")
@MapperScan("com.culture.mapper")
public class CultureApplication {

    public static void main(String[] args) {
        SpringApplication.run(CultureApplication.class, args);
    }

}
