package com.hmdp.dto;

import lombok.Data;

/**
 * 知识库创建/更新表单。tenant_id 不从请求取,一律由登录态推导,杜绝越权。
 */
@Data
public class KnowledgeBaseFormDTO {
    private String name;
    private String description;
}
