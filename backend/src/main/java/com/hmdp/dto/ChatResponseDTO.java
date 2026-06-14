package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 问答响应(M3 非流式)。流式版本通过 SSE 先发一条 meta(含 conversationId+sources)再逐字推 answer。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseDTO {

    /** 会话 ID(首轮由后端生成,前端续轮带回) */
    private String conversationId;

    /** 模型回答 */
    private String answer;

    /** 引用溯源:本次回答命中的知识片段来源 */
    private List<Source> sources;

    /** 命中的知识片段来源(引用溯源) */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        /** 文档名 */
        private String docName;
        /** 相似度得分(越高越相关) */
        private Double score;
        /** 片段摘要(截断展示) */
        private String snippet;
    }
}
