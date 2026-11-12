package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HelloController.class)
class HelloControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void helloWithDefaultName() throws Exception {
        mvc.perform(get("/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello, Spring!"));
    }

    @Test
    void helloWithName() throws Exception {
        mvc.perform(get("/hello").param("name", "Clarke"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello, Clarke!"));
    }

    @Test
    void nameTooLongIsRejected() throws Exception {
        mvc.perform(get("/hello").param("name", "x".repeat(21)))
                .andExpect(status().isBadRequest());
    }
}
