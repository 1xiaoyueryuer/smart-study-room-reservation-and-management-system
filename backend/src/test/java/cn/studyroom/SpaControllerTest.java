package cn.studyroom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaControllerTest {
    private MockMvc http;

    @BeforeEach
    void setUp() {
        http = MockMvcBuilders.standaloneSetup(new SpaController()).build();
    }

    @Test
    void allFrontendHistoryRoutesForwardToTheSpaEntry() throws Exception {
        for (String path : new String[]{
            "/", "/login", "/room-reviews", "/booking", "/mine", "/mine/42/checkin",
            "/complaints", "/complaints/42", "/appeals/new", "/appeals/42",
            "/waitlist", "/analytics", "/notifications", "/profile", "/admin"
        }) {
            http.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void unmatchedPathsDoNotFallBackToTheSpa() throws Exception {
        http.perform(get("/api/health")).andExpect(status().isNotFound());
        http.perform(get("/assets/app.js")).andExpect(status().isNotFound());
        http.perform(get("/not-a-frontend-route")).andExpect(status().isNotFound());
    }
}
