package com.shop.common.spring.mapping;

import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.modelmapper.spi.ValueReader;

import static org.assertj.core.api.Assertions.assertThat;

// public: the fixture record below must have a public declaring class, or
// reflection on its accessors throws IllegalAccessException (silently
// swallowed by the reader). Production DTO records are public top-level types.
public class RecordValueReaderTest {

    // public: reflection on accessors of non-public record classes throws
    // IllegalAccessException (silently swallowed by the reader), so fixtures
    // must be public to mirror production DTOs.
    public record SourceRequest(String title, String slug, Integer quantity) {}

    static class TargetEntity {
        private String title;
        private String slug;
        private Integer quantity;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getSlug() {
            return slug;
        }

        public void setSlug(String slug) {
            this.slug = slug;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }

    private ModelMapper strictMapperWithRecordValueReader() {
        ModelMapper mapper = new ModelMapper();
        mapper.getConfiguration()
            .setMatchingStrategy(MatchingStrategies.STRICT)
            .setSkipNullEnabled(true)
            .setFieldMatchingEnabled(true)
            .addValueReader(new RecordValueReader());
        return mapper;
    }

    @Test
    void mapsRecordFieldsToEntity() {
        ModelMapper mapper = strictMapperWithRecordValueReader();

        TargetEntity entity = mapper.map(new SourceRequest("T", "s", 3), TargetEntity.class);

        assertThat(entity.getTitle()).isEqualTo("T");
        assertThat(entity.getSlug()).isEqualTo("s");
        assertThat(entity.getQuantity()).isEqualTo(3);
    }

    @Test
    void patchSkipsNullRecordComponents() {
        ModelMapper mapper = strictMapperWithRecordValueReader();

        TargetEntity existing = new TargetEntity();
        existing.setTitle("old");
        existing.setSlug("old-slug");
        existing.setQuantity(7);

        mapper.map(new SourceRequest("T2", null, null), existing);

        assertThat(existing.getTitle()).isEqualTo("T2");
        assertThat(existing.getSlug()).isEqualTo("old-slug");
        assertThat(existing.getQuantity()).isEqualTo(7);
    }

    @Test
    void memberNamesReturnsRecordComponentNames() {
        assertThat(new RecordValueReader().memberNames(new SourceRequest("T", "s", 3)))
            .containsExactlyInAnyOrder("title", "slug", "quantity");
    }

    @Test
    void getReturnsComponentValue() {
        assertThat(new RecordValueReader().get(new SourceRequest("T", "s", 3), "title")).isEqualTo("T");
    }

    @Test
    void getReturnsNullForUnknownMember() {
        assertThat(new RecordValueReader().get(new SourceRequest("T", "s", 3), "nonexistent")).isNull();
    }

    @Test
    void getReturnsNullForNullSource() {
        assertThat(new RecordValueReader().get(null, "title")).isNull();
    }

    @Test
    void getMemberReturnsMemberWithMatchingType() {
        ValueReader.Member<Record> member = new RecordValueReader().getMember(new SourceRequest("T", "s", 3), "title");

        assertThat(member).isNotNull();
        assertThat(member.getValueType()).isEqualTo(String.class);
        assertThat(member.get(new SourceRequest("T", "s", 3), "title")).isEqualTo("T");
    }

    @Test
    void getMemberReturnsNullForNullValuedComponent() {
        assertThat(new RecordValueReader().getMember(new SourceRequest("T", null, null), "slug")).isNull();
    }
}
