package hnist.communication.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.GroupMember;
import hnist.communication.mapper.GroupMemberMapper;
import hnist.communication.service.impl.AccessControlServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessControlServiceTest {

    @Mock
    GroupMemberMapper groupMemberMapper;

    private AccessControlService service;

    @BeforeAll
    static void tableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "access-unit-test"),
                GroupMember.class);
    }

    @BeforeEach
    void setUp() {
        service = new AccessControlServiceImpl(groupMemberMapper);
    }

    @Test
    void selfAndAdminBypassGroupChecks() {
        assertDoesNotThrow(() -> service.requireSelfOrAdmin(7L, authentication(7L), "denied"));
        assertDoesNotThrow(() -> service.requireSelfOrAdmin(99L, authentication(7L, "ROLE_ADMIN"), "denied"));
        assertDoesNotThrow(() -> service.requireSameGroupOrAdmin(99L,
                authentication(7L, "ROLE_ADMIN"), "denied"));
    }

    @Test
    void ordinaryUserCannotAccessUnrelatedUser() {
        when(groupMemberMapper.selectList(any())).thenReturn(List.of());

        assertThrows(BusinessException.class,
                () -> service.requireSameGroupOrAdmin(99L, authentication(7L), "denied"));
    }

    @Test
    void sameGroupMembersCanAccessSharedLocation() {
        GroupMember membership = GroupMember.builder().groupId(5L).userId(7L).build();
        when(groupMemberMapper.selectList(any())).thenReturn(List.of(membership));
        when(groupMemberMapper.selectCount(any())).thenReturn(1L);

        assertDoesNotThrow(() -> service.requireSameGroupOrAdmin(99L, authentication(7L), "denied"));
        assertTrue(service.isSameGroup(7L, 99L));
    }

    @Test
    void groupMembershipIsRequiredForGroupScopedResources() {
        when(groupMemberMapper.selectCount(any())).thenReturn(0L);
        assertThrows(BusinessException.class,
                () -> service.requireGroupMemberOrAdmin(5L, authentication(7L), "denied"));

        when(groupMemberMapper.selectCount(any())).thenReturn(1L);
        assertDoesNotThrow(() -> service.requireGroupMemberOrAdmin(5L, authentication(7L), "denied"));
    }

    @Test
    void sameGroupUserIdsAlwaysContainsSelfAndHandlesMissingGroup() {
        when(groupMemberMapper.selectList(any())).thenReturn(List.of());
        assertEquals(Set.of(7L), service.sameGroupUserIds(7L));
        assertFalse(service.isSameGroup(null, 7L));
        assertFalse(service.isGroupMember(null, 7L));
    }

    @Test
    void unauthenticatedCallsAreRejected() {
        assertThrows(BusinessException.class, () -> service.currentUserId(null));
    }

    private static Authentication authentication(Long userId, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new UsernamePasswordAuthenticationToken(userId, null, authorities);
    }
}
