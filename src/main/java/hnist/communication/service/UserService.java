package hnist.communication.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.dto.LoginRequest;
import hnist.communication.dto.UserRequest;
import hnist.communication.dto.UserUpdateRequest;
import hnist.communication.vo.UserVO;

/** 用户账号、资料与登录会话管理。 */
public interface UserService {
    Page<UserVO> listUsers(int page, int size, String keyword);
    UserVO getUser(Long id);
    UserVO createUser(UserRequest req);
    UserVO updateUser(Long id, UserUpdateRequest req);
    void deleteUser(Long id, Long currentUserId);
    void batchMoveToGroup(Long fromGroupId, Long toGroupId);
    UserVO login(LoginRequest req);
    UserVO loginBackend(LoginRequest req);
    UserVO getProfile(Long userId);
    UserVO updateProfile(Long userId, UserUpdateRequest req);
    void updatePassword(Long userId, String oldPassword, String newPassword);
    void switchGroup(Long userId, Long groupId);
}
