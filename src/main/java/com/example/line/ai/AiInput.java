package com.example.line.ai;

import java.time.Instant;

/** 当前私聊业务输入与接收顺序，凭证仅作为短时发送参数，绝不进入该对象。 */
public record AiInput(String userId,String messageId,String text,com.fasterxml.jackson.databind.JsonNode content,String correlationId,Instant occurredAt,
                       Instant receivedAt,long sequence) {}
