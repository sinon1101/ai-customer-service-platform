package com.hmdp.dto;

import lombok.Data;

/**
 * 文档上传表单(文本/Markdown 直传)。tenant_id/kbId 不从 body 取,
 * kbId 走路径、tenantId 由登录态推导,杜绝越权。
 */
@Data
public class DocUploadDTO {
    /** 文档名 */
    private String name;
    /** 原始文本内容 */
    private String content;
    /** 来源类型:TEXT / MD,缺省 TEXT */
    private String sourceType;
}
