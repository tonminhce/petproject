package com.shop.authservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Builder
@AllArgsConstructor
public class UserResponse {
    private UUID id;
    private String fullname;
    private String username;
    private String email;
    private String gender;
    private String phone;
    private String avatar;
}