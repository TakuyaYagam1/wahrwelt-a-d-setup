package ru.serega6531.packmate.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SameOriginMutationFilterTest {

    private final SameOriginMutationFilter filter = new SameOriginMutationFilter();

    @Test
    void acceptsSameOriginBrowserApiPost() throws Exception {
        MockHttpServletRequest request = postRequest("http://127.0.0.1:65000");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void rejectsForeignOriginApiPost() throws Exception {
        MockHttpServletRequest request = postRequest("https://attacker.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsString().contains("Источник запроса"));
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsApiPostWithoutOrigin() throws Exception {
        MockHttpServletRequest request = postRequest(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsForeignOriginWebSocketEndpointPost() throws Exception {
        MockHttpServletRequest request = postRequest("https://attacker.example");
        request.setRequestURI("/api/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsForeignOriginWebSocketHandshake() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/ws");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(65000);
        request.addHeader("Origin", "https://attacker.example");
        request.addHeader("Upgrade", "websocket");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void acceptsSameOriginWebSocketHandshake() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/ws");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(65000);
        request.addHeader("Origin", "http://127.0.0.1:65000");
        request.addHeader("Upgrade", "websocket");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    private MockHttpServletRequest postRequest(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/service/");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(65000);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }
}
