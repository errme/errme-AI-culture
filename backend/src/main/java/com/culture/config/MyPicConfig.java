package com.culture.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


//新增加一个类用来添加虚拟路径映射
@Configuration
public class MyPicConfig implements WebMvcConfigurer {


    private static final String[] CLASSPATH_RESOURCE_LOCATIONS = {
            "classpath:/META-INF/resources/", "classpath:/resources/",
            "classpath:/static/", "classpath:/public/"};

    //上传地址
    @Value("${avatar.upload.path}")
    private String filePath;

    /** 富文本正文媒体目录（图片/视频），URL 前缀 /upload/media/** */
    @Value("${editor.upload.path}")
    private String editorUploadPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String os = System.getProperty("os.name");
        if (os.toLowerCase().startsWith("win")) {  //如果是Windows系统
            registry.addResourceHandler("/static/upload/**")
                    // /apple/**表示在磁盘apple目录下的所有资源会被解析为以下的路径
                    .addResourceLocations("file:" + filePath); //媒体资源
        } else {  //linux 和mac
            registry.addResourceHandler("/culture/upload/**")
                    .addResourceLocations("file:" + filePath);
        }

        // 富文本正文图片/视频：项目根 upload/media 目录（图片 image/、视频 video/）
        String editorLocation = editorUploadPath.endsWith("/") || editorUploadPath.endsWith("\\")
                ? editorUploadPath : editorUploadPath + java.io.File.separator;
        registry.addResourceHandler("/upload/media/**")
                .addResourceLocations("file:" + editorLocation);


        if (!registry.hasMappingForPattern("/webjars/**")) {
            registry.addResourceHandler("/webjars/**").addResourceLocations(
                    "classpath:/META-INF/resources/webjars/");
            registry.addResourceHandler("swagger-ui.html")
                    .addResourceLocations("classpath:/META-INF/resources/");
        }
        if (!registry.hasMappingForPattern("/**")) {
            registry.addResourceHandler("/**").addResourceLocations(
                    CLASSPATH_RESOURCE_LOCATIONS);
        }

    }
}
