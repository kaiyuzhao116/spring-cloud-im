package com.song.api.im.dto;

import java.io.Serializable;

/**
 * 发消息命令（Dubbo 入参）。
 *
 * <p>没有 from 字段的来源问题：{@code fromUid} 由调用方（ws-gateway）
 * 从连接身份里取，绝不来自客户端帧。</p>
 */
public class SendMessageCmd implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 发送者 userId（来自连接身份） */
    private Long fromUid;

    /** 接收者 userId */
    private Long toUid;

    /** 客户端消息 ID（幂等键） */
    private String clientMsgId;

    // 在 content 下面加
    private String fromConnId;
    public String getFromConnId() {
        return fromConnId;
    }
    public void setFromConnId(String fromConnId) {
        this.fromConnId = fromConnId;
    }

    /** 文本内容（≤2000） */
    private String content;

    public Long getFromUid() {
        return fromUid;
    }

    public void setFromUid(Long fromUid) {
        this.fromUid = fromUid;
    }

    public Long getToUid() {
        return toUid;
    }

    public void setToUid(Long toUid) {
        this.toUid = toUid;
    }

    public String getClientMsgId() {
        return clientMsgId;
    }

    public void setClientMsgId(String clientMsgId) {
        this.clientMsgId = clientMsgId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
