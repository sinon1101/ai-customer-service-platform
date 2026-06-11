package com.hmdp.service;

public interface IChatService {

    /** 最小对话:直连百炼,返回模型回复文本(M1 冒烟,无 RAG / 无多轮) */
    String chat(String message);
}
