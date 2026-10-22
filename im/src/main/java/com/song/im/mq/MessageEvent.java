package com.song.im.mq;

import java.io.Serializable;

/**
 * 消息已落库事件（发给 ws-gateway 做在线推送）。
 *
 * <p>带上 content 是为了让消费端不必回查数据库；消息体很小，单聊场景可接受。</p>
 */
public class MessageEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long msgId;

    private String conversationId;

    private Long seq;

    private Long fromUid;

    private Long toUid;

    private String content;

    private Long ts;

    public MessageEvent() {
    }

    public MessageEvent(Long msgId, String conversationId, Long seq, Long fromUid,
                        Long toUid, String content, Long ts) {
        this.msgId = msgId;
        this.conversationId = conversationId;
        this.seq = seq;
        this.fromUid = fromUid;
        this.toUid = toUid;
        this.content = content;
        this.ts = ts;
    }

    public Long getMsgId() {
        return msgId;
    }

    public void setMsgId(Long msgId) {
        this.msgId = msgId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getTs() {
        return ts;
    }

    public void setTs(Long ts) {
        this.ts = ts;
    }
}
