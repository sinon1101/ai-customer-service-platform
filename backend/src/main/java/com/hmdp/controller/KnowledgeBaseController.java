package com.hmdp.controller;

import com.hmdp.dto.DocUploadDTO;
import com.hmdp.dto.KnowledgeBaseFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IKbDocumentService;
import com.hmdp.service.IKnowledgeBaseService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 知识库 CRUD + 文档摄入。全部需登录,且服务层强制按当前租户隔离。
 */
@RestController
@RequestMapping("/kb")
public class KnowledgeBaseController {

    @Resource
    private IKnowledgeBaseService knowledgeBaseService;

    @Resource
    private IKbDocumentService kbDocumentService;

    @PostMapping
    public Result create(@RequestBody KnowledgeBaseFormDTO form) {
        return knowledgeBaseService.create(form);
    }

    @GetMapping("/list")
    public Result list() {
        return knowledgeBaseService.listCurrentTenant();
    }

    @GetMapping("/{id}")
    public Result get(@PathVariable("id") Long id) {
        return knowledgeBaseService.getOne(id);
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable("id") Long id, @RequestBody KnowledgeBaseFormDTO form) {
        return knowledgeBaseService.update(id, form);
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable("id") Long id) {
        return knowledgeBaseService.remove(id);
    }

    // ───────────────────── 文档摄入(M2)─────────────────────

    /** 上传文档(文本/Markdown)到指定知识库:立即返回 docId,后台异步切片向量化 */
    @PostMapping("/{kbId}/documents")
    public Result uploadDocument(@PathVariable("kbId") Long kbId, @RequestBody DocUploadDTO form) {
        return kbDocumentService.upload(kbId, form);
    }

    /** 列出某知识库下的文档(带处理状态,供前端轮询进度) */
    @GetMapping("/{kbId}/documents")
    public Result listDocuments(@PathVariable("kbId") Long kbId) {
        return kbDocumentService.listByKb(kbId);
    }
}
