package com.shop.authservice.controller;

import com.shop.authservice.dto.request.RegisterRequest;
import com.shop.common.core.viewmodel.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    @PostMapping("/sign-up")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest req){

    }
}
