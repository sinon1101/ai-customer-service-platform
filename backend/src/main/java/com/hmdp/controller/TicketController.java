package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.TransferRequestDTO;
import com.hmdp.service.ITicketService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工坐席工单(M6)。需登录(走多租户鉴权链)。
 * <ul>
 *   <li>访客侧:{@code POST /ticket/transfer} 转人工建单、{@code POST /ticket/{id}/close} 结束、{@code GET /ticket/{id}} 详情+历史。</li>
 *   <li>坐席侧:{@code GET /ticket/pending} 待接入池、{@code POST /ticket/{id}/claim} 抢单、{@code GET /ticket/mine} 我的会话。</li>
 * </ul>
 * 实时收发消息走 WebSocket {@code /ws/chat}(见 {@link com.hmdp.ws.ChatWebSocketHandler})。
 */
@RestController
@RequestMapping("/ticket")
public class TicketController {

    @Resource
    private ITicketService ticketService;

    /** 访客转人工:生成待接入工单(同对话幂等) */
    @PostMapping("/transfer")
    public Result transfer(@RequestBody TransferRequestDTO form) {
        return ticketService.transfer(form);
    }

    /** 坐席:待接入池 */
    @GetMapping("/pending")
    public Result pending() {
        return ticketService.listPending();
    }

    /** 坐席抢单(Redisson 锁 + DB 条件更新双保险) */
    @PostMapping("/{id}/claim")
    public Result claim(@PathVariable("id") Long id) {
        return ticketService.claim(id);
    }

    /** 结束会话(访客或接单坐席) */
    @PostMapping("/{id}/close")
    public Result close(@PathVariable("id") Long id) {
        return ticketService.close(id);
    }

    /** 坐席:我当前已接入的会话 */
    @GetMapping("/mine")
    public Result mine() {
        return ticketService.mine();
    }

    /** 工单详情 + 历史消息(进入会话时回看) */
    @GetMapping("/{id}")
    public Result detail(@PathVariable("id") Long id) {
        return ticketService.detail(id);
    }
}
