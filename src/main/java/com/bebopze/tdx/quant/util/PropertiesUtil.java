package com.bebopze.tdx.quant.util;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.util.Properties;


/**
 * @author: bebopze
 * @date: 2025/5/4
 */
@Slf4j
public class PropertiesUtil {


    @SneakyThrows
    public static String getProperty(String key) {

        Properties props = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        String value = props.getProperty(key);

        log.info("getProperty     >>>     key : {} , value : {}", key, value);
        return value;
    }


    /**
     * 通达信 - 根目录
     *
     * @return
     */
    public static String getTdxPath() {
        return getProperty("tdx-path");
    }


    public static void main(String[] args) {
        getTdxPath();
    }

}
