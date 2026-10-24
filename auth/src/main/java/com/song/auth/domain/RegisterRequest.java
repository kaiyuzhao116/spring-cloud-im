package com.song.auth.domain;

/**
 * 注册请求。
 *
 * <p>最小字段：账号 + 密码 + 昵称。手机号/邮箱等暂不引入（没有验证码通道时是负担）。</p>
 */
public class RegisterRequest {

    private String account;

    private String password;

    private String nickname;

    private String deviceType;

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }
}
