package com.shop.common.core.data;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(AbstractMappedEntityTest.AuditingConfig.class)
class AbstractMappedEntityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaAuditing
    static class AuditingConfig {}

    @Entity
    static class TestEntity extends AbstractMappedEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        public Long getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    void persistsWithAuditAndSoftDeleteFields() {
        TestEntity entity = new TestEntity();
        entity.setName("test");
        testEntityManager.persistAndFlush(entity);

        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void markDeletedSetsFlags() {
        TestEntity entity = new TestEntity();
        entity.setName("test");
        testEntityManager.persistAndFlush(entity);

        entity.markDeleted("alice");

        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getDeletedAt()).isNotNull();
        assertThat(entity.getDeletedBy()).isEqualTo("alice");
    }
}