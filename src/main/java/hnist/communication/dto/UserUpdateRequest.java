package hnist.communication.dto;

import lombok.Data;

@Data
public class UserUpdateRequest {
    private String username;
    private String password;
    private String nickname;
    private String phone;
    private String email;
    private String sex;
    private Integer role;
}
