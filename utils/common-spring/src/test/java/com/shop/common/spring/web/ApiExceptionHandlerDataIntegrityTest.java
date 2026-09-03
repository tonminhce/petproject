package com.shop.common.spring.web;

import com.shop.common.spring.web.exception.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R2 test — @WebMvcTest slice test enforcing that DataIntegrityViolationException
 * maps to HTTP 409 CONFLICT and never leaks DB schema details (table, column, constraint, SQL).
 * (Migrated from utils/common-patterns to utils/common-spring).
 */
@WebMvcTest(controllers = ApiExceptionHandlerDataIntegrityTest.StubDataIntegrityController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandler.class, ApiExceptionHandlerDataIntegrityTest.StubDataIntegrityController.class})
class ApiExceptionHandlerDataIntegrityTest {

    @Autowired
    private MockMvc mvc;

    @RestController
    static class StubDataIntegrityController {
        @PostMapping("/__test__/trigger/data-integrity")
        public void triggerDataIntegrityViolation() {
            SQLException sql = new SQLException(
                    "ERROR: duplicate key value violates unique constraint \"users_username_key\"\n"
                            + "  Detail: Key (username)=(admin) already exists.",
                    "23505");
            throw new DataIntegrityViolationException("could not execute statement", sql);
        }
    }

    @Test
    void dataIntegrityViolationReturns409WithGenericMessage() throws Exception {
        mvc.perform(post("/__test__/trigger/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ERR-0409"))
                .andExpect(jsonPath("$.message").value(not(containsString("users_username_key"))))
                .andExpect(jsonPath("$.message").value(not(containsString("username"))))
                .andExpect(jsonPath("$.message").value(not(containsString("constraint"))))
                .andExpect(jsonPath("$.message").value(not(containsString("SQL"))))
                .andExpect(jsonPath("$.message").value(not(containsString("23505"))))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
