package com.hmdp.dto;

import lombok.Data;

/**
 * 最小对话请求(M1 冒烟,无 RAG / 无多轮)。
 */
@Data
public class ChatRequestDTO {
    /** 用户问题 */
    private String message;
}
