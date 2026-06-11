package com.hmdp.controller;

import com.hmdp.dto.KnowledgeBaseFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IKnowledgeBaseService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 知识库 CRUD。全部需登录,且服务层强制按当前租户隔离。
 */
@RestController
@RequestMapping("/kb")
public class KnowledgeBaseController {

    @Resource
    private IKnowledgeBaseService knowledgeBaseService;

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
}
