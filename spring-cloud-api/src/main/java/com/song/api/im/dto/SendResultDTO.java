package com.song.api.im.dto;

import java.io.Serializable;

/**
 * 发消息结果（Dubbo 出参）：客户端靠 cid 认领，靠 seq 排序。
 */
public class SendResultDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long msgId;

    private Long seq;

    private String conversationId;

    public SendResultDTO() {
    }

    public SendResultDTO(Long msgId, Long seq, String conversationId) {
        this.msgId = msgId;
        this.seq = seq;
        this.conversationId = conversationId;
    }

    public Long getMsgId() {
        return msgId;
    }

    public void setMsgId(Long msgId) {
        this.msgId = msgId;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
}
