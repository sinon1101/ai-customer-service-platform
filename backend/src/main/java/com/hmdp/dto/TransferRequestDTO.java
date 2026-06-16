package com.hmdp.dto;

import lombok.Data;

/**
 * 转人工请求(M6):访客把当前对话转给人工坐席,生成待接入工单。
 */
@Data
public class TransferRequestDTO {

    /** 当前对话ID(链到 chat 多轮历史;为空则仅作为一次独立咨询) */
    private String conversationId;

    /** 关联知识库(可空) */
    private Long kbId;

    /** 转人工原因:USER_REQUEST / BOT_FAILED / NOT_FOUND(默认 USER_REQUEST) */
    private String reason;

    /** 触发转人工的最后一问(给坐席的上下文) */
    private String lastQuestion;
}
