package hnist.communication.service;

import hnist.communication.exception.BusinessException;
import hnist.communication.entity.ChatMessage;
import hnist.communication.mapper.ChatMessageMapper;
import hnist.communication.service.impl.ChatMessageServiceImpl;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatMessageCreationTest {
    @Test void clientCannotChooseIdTimestampOrRecallState() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ChatMessageServiceImpl service = new ChatMessageServiceImpl(mapper, mock(ChatQuoteService.class));
        ChatMessage message = ChatMessage.builder().id(77L).senderId(1L).receiverId(2L).content("测试")
            .msgType("recalled").createdAt(LocalDateTime.now().plusDays(1)).recalledAt(LocalDateTime.now()).build();
        LocalDateTime before = LocalDateTime.now();
        service.create(message);
        assertNull(message.getId()); assertNull(message.getRecalledAt()); assertEquals("text", message.getMsgType());
        assertFalse(message.getCreatedAt().isBefore(before)); assertFalse(message.getCreatedAt().isAfter(LocalDateTime.now()));
        verify(mapper).insert(message);
    }
    @Test void emptyContentAndInvalidReceiverAreRejected() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ChatMessageServiceImpl service = new ChatMessageServiceImpl(mapper, mock(ChatQuoteService.class));
        assertThrows(BusinessException.class, () -> service.create(ChatMessage.builder().receiverId(2L).content("").build()));
        assertThrows(BusinessException.class, () -> service.create(ChatMessage.builder().content("测试").build()));
        verifyNoInteractions(mapper);
    }
}
