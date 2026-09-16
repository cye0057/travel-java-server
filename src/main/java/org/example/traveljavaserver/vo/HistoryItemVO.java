package org.example.traveljavaserver.vo;

/** 历史会话列表项（Profile 页展示用） */
public record HistoryItemVO(String sessionId, String title, String updatedAt) {}
