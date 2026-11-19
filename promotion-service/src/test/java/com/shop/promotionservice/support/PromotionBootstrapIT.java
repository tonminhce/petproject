package com.shop.promotionservice.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.shop.promotionservice.repository.CampaignRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionBootstrapIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CampaignRepository campaignRepository;

    @Test
    void contextBootsAndLiquibaseSchemaIsApplied() {
        assertThat(applicationContext).isNotNull();

        List<String> tables = jdbcTemplate.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String.class);

        assertThat(tables).contains(
            "campaign",
            "coupon_usage_reservation",
            "outbox_events",
            "databasechangelog",
            "databasechangeloglock");

        jdbcTemplate.execute("TRUNCATE TABLE coupon_usage_reservation, campaign CASCADE");
        assertThat(campaignRepository.count()).isZero();
    }
}
