package hnist.communication.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.entity.CallRecord;
import hnist.communication.entity.User;
import hnist.communication.mapper.CallRecordMapper;
import hnist.communication.mapper.UserMapper;
import hnist.communication.service.impl.CallRecordServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CallRecordTypeTest {
    @BeforeAll static void metadata() {
        for (Class<?> type : List.of(CallRecord.class, User.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "call-type-test"), type);
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3}) void createRetryAndEditPreserveDistinctTypes(int type) {
        CallRecordMapper records = mock(CallRecordMapper.class); UserMapper users = mock(UserMapper.class);
        CallRecordServiceImpl service = new CallRecordServiceImpl(records, users);
        CallRecord input = CallRecord.builder().roomId("type_fixture").callerId(1L).mediaType(2).callType(type).build();
        assertEquals(type, service.create(input).getCallType()); verify(records).insert(input);
        CallRecord existing = CallRecord.builder().id(7L).roomId("type_fixture").callerId(1L).callType(type).build();
        when(records.selectOne(any())).thenReturn(existing); when(records.selectById(7L)).thenReturn(existing);
        assertEquals(type, service.create(input).getCallType()); verify(records).updateById(existing);
        assertEquals(type, service.update(7L, CallRecord.builder().callType(type).build()).getCallType());
        assertEquals(type, service.get(7L).getCallType());
    }
    @ParameterizedTest @ValueSource(ints = {2, 3}) void listFiltersConferenceAndGroupVideoSeparately(int type) {
        CallRecordMapper records = mock(CallRecordMapper.class); UserMapper users = mock(UserMapper.class);
        CallRecordServiceImpl service = new CallRecordServiceImpl(records, users);
        when(records.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<CallRecord> query = invocation.getArgument(1);
            assertTrue(query.getSqlSegment().contains("call_type")); assertTrue(query.getParamNameValuePairs().containsValue(type));
            Page<CallRecord> page = invocation.getArgument(0); page.setRecords(List.of(CallRecord.builder().id(7L).callType(type).build()));
            return page;
        });
        assertEquals(type, service.list(1, 10, null, type, null, null, null, null, 1L, true).getRecords().get(0).getCallType());
    }
}
