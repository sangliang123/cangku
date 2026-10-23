package com.baiyun.pet

/**
 * 一条聊天消息。
 *
 * @param text   消息内容
 * @param isUser true = 用户发的（靠右、蓝色气泡）；false = 白云说的（靠左、灰色气泡）
 *
 * 同一条数据类既用于气泡列表，也用于 DeepSeekChatManager 里的对话历史。
 */
data class Message(
    val text: String,
    val isUser: Boolean
)
