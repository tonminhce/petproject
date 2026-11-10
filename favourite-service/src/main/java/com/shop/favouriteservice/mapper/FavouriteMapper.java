package com.shop.favouriteservice.mapper;

import com.shop.favouriteservice.dto.response.FavouriteResponse;
import com.shop.favouriteservice.entity.Favourite;
import org.springframework.stereotype.Component;

@Component
public class FavouriteMapper {

    public FavouriteResponse toResponse(Favourite favourite) {
        return new FavouriteResponse(
                favourite.getId(),
                favourite.getUserId(),
                favourite.getProductId(),
                favourite.getCreatedAt()
        );
    }

}
