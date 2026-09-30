package com.wastesim.controller;

import com.wastesim.service.SimulationService;
import com.wastesim.tool.SimulationTool;
import com.wastesim.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HttpErrorHandlingTest {
    private MockMvc mvc;
    private SimulationTool tool;

    @BeforeEach
    void setUp() {
        tool = mock(SimulationTool.class);
        mvc = standaloneSetup(new SimulationController(mock(SimulationService.class), tool))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void invalidSeedIsBadRequest() throws Exception {
        mvc.perform(post("/api/simulation/run").param("seed", "abc")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("BAD_REQUEST"));
        verifyNoInteractions(tool);
    }

    @Test
    void wrongMethodPreservesAllowHeader() throws Exception {
        mvc.perform(get("/api/simulation/run"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
    }

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        mvc.perform(post("/api/simulation/run").contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/api/simulation/run").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("BAD_REQUEST"));
    }

    @Test
    void unexpectedFailureRemains500() throws Exception {
        when(tool.validate(any())).thenThrow(new IllegalStateException("private details"));
        mvc.perform(post("/api/simulation/run").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("message").value("서버 내부 오류가 발생했습니다."));
    }
}
