package com.song.wsgateway.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * WebSocket 消息帧（最小版：4 种帧、6 个字段）。
 *
 * <p>协议示例：</p>
 * <pre>
 * ① 发消息    客户端 → 服务端   {"type":"MSG","cid":"c1","to":2,"content":"在吗"}
 * ② 发送成功  服务端 → 发送方   {"type":"ACK","cid":"c1","seq":1}
 * ③ 收到消息  服务端 → 接收方   {"type":"MSG","seq":1,"from":1,"content":"在吗"}
 * ④ 心跳      {"type":"PING"} / {"type":"PONG"}
 * </pre>
 *
 * <p>字段归属铁律：</p>
 * <ul>
 *   <li>{@code from} / {@code seq} 只能由服务端生成，客户端传了也不采用（防冒充）</li>
 *   <li>{@code cid} 必须由客户端生成，服务端原样回传（客户端据此认领 ACK）</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Frame {

    /** MSG / ACK / PING / PONG */
    private String type;

    /** 客户端消息ID（上行必填；ACK 原样回传） */
    private String cid;

    /** 接收方 userId（上行 MSG 必填） */
    private Long to;

    /** 发送方 userId（下行 MSG 由服务端填充） */
    private Long from;

    /** 会话内序号（ACK / 下行 MSG 由服务端填充） */
    private Long seq;

    /** 文本内容（≤2000 字符） */
    private String content;

    public Frame() {
    }

    public Frame(String type, String cid, Long to, Long from, Long seq, String content) {
        this.type = type;
        this.cid = cid;
        this.to = to;
        this.from = from;
        this.seq = seq;
        this.content = content;
    }

    /** 上行：客户端发消息 */
    public static Frame up(String cid, Long to, String content) {
        return new Frame(FrameType.MSG.name(), cid, to, null, null, content);
    }

    /** 下行：发送成功确认 */
    public static Frame ack(String cid, Long seq) {
        return new Frame(FrameType.ACK.name(), cid, null, null, seq, null);
    }

    /** 下行：推给接收方 */
    public static Frame down(Long from, Long seq, String content) {
        return new Frame(FrameType.MSG.name(), null, null, from, seq, content);
    }

    public static Frame ping() {
        return new Frame(FrameType.PING.name(), null, null, null, null, null);
    }

    public static Frame pong() {
        return new Frame(FrameType.PONG.name(), null, null, null, null, null);
    }

    /** 解析类型，未知返回 null */
    public FrameType frameType() {
        return FrameType.of(type);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCid() {
        return cid;
    }

    public void setCid(String cid) {
        this.cid = cid;
    }

    public Long getTo() {
        return to;
    }

    public void setTo(Long to) {
        this.to = to;
    }

    public Long getFrom() {
        return from;
    }

    public void setFrom(Long from) {
        this.from = from;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return "Frame{type=" + type + ", cid=" + cid + ", to=" + to
                + ", from=" + from + ", seq=" + seq + ", content=" + content + '}';
    }
}
