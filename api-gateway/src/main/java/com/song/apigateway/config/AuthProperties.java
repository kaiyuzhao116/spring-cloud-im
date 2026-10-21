package com.song.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权配置。
 */
@Component
@ConfigurationProperties(prefix = "gateway.auth")
public class AuthProperties {

    /**
     * 与 auth 服务一致的 HS256 密钥（生产应改为 RS256 公钥）。
     */
    private String secret;

    /**
     * 白名单路径，支持 Ant 风格。
     */
    private List<String> whiteList = new ArrayList<>();

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public List<String> getWhiteList() {
        return whiteList;
    }

    public void setWhiteList(List<String> whiteList) {
        this.whiteList = whiteList;
    }
}
