package com.hmdp.dto;

import lombok.Data;

/**
 * RAG 问答请求(M3)。
 */
@Data
public class ChatRequestDTO {
    /** 用户问题 */
    private String message;

    /** 指定知识库;为空则在当前租户的全部知识库里召回 */
    private Long kbId;

    /** 会话 ID:首轮可不传(后端生成并回传),续轮带上以延续多轮上下文 */
    private String conversationId;
}
