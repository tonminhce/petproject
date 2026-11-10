package com.shop.mediaservice.service;

import com.shop.mediaservice.dto.response.MediaResponse;
import org.springframework.web.multipart.MultipartFile;

public interface MediaUploadService {

    MediaResponse upload(MultipartFile file);
}
