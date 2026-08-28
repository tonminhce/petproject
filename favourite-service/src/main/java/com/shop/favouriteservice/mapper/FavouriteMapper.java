package com.shop.favouriteservice.mapper;

import com.shop.favouriteservice.dto.response.FavouriteResponse;
import com.shop.favouriteservice.entity.Favourite;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class FavouriteMapper {

    private final ModelMapper modelMapper;

    public FavouriteMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public FavouriteResponse toResponse(Favourite favourite) {
        // Manual mapping — only 4 fields, ModelMapper overhead exceeds benefit.
        // Same approach as auth-service UserMapper.toResponse.
        return new FavouriteResponse(
                favourite.getId(),
                favourite.getUserId(),
                favourite.getProductId(),
                favourite.getCreatedAt()
        );
    }

    // No toEntity() — FavouriteServiceImpl.create() builds the entity via
    // .builder() directly (only 2 user-supplied fields + generated id).
}
