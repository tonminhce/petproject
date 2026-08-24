package com.shop.authservice.mapper;

import com.shop.authservice.dto.request.RegisterRequest;
import com.shop.authservice.dto.response.UserResponse;
import com.shop.authservice.entity.User;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {
    private final ModelMapper modelMapper;

    public UserMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .fullname(user.getFullName())
                .username(user.getUsername())
                .email(user.getEmail())
                .gender(user.getGender())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .build();
    }

    public User toEntity(RegisterRequest request) {
        return modelMapper.map(request, User.class);
    }

    public void mergeToEntity(RegisterRequest source, User target) {
        modelMapper.map(source, target);
    }
}
