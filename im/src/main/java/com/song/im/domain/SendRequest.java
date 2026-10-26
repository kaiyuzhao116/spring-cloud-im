package com.song.im.domain;

/**
 * 发消息请求体。
 *
 * <p>注意：没有 {@code from} 字段——发送者一律从 {@code X-User-Id} 取，
 * 客户端传了也不认，防止冒充。</p>
 */
public class SendRequest {
    private String fromConnId;

    /** 接收方 userId */
    private Long to;

    /** 客户端消息 ID（幂等键，必传） */
    private String cid;

    /** 文本内容（≤2000 字符） */
    private String content;

    public String getFromConnId() {
        return fromConnId;
    }

    public void setFromConnId(String fromConnId) {
        this.fromConnId = fromConnId;
    }
    public Long getTo() {
        return to;
    }

    public void setTo(Long to) {
        this.to = to;
    }

    public String getCid() {
        return cid;
    }

    public void setCid(String cid) {
        this.cid = cid;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
