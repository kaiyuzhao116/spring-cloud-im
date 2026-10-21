package com.song.auth.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class LoginRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "账号不能为空")
    @Size(max = 64, message = "账号长度不能超过 64")
    private String account;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6~32 之间")
    private String password;

    /**
     * APP / WEB / PC，默认 APP。
     */
    private String deviceType = "APP";

}
