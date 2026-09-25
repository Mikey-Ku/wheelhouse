package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.account.AccountService;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * A browser, for tests: signs in through {@link FakeIdentities} and sends its session cookie with
 * every call.
 * Paths are URL templates, so a literal space in one is encoded rather than written as %20.
 */
public final class Api {

    private final MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private Cookie session;
    private int lastStatus;

    public Api(WebApplicationContext context) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /** Signed up under a random name, so tests sharing a database never collide. */
    public static Api signedUp(WebApplicationContext context) throws Exception {
        Api api = new Api(context);
        api.signUp("t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        return api;
    }

    /** Somebody new signing in for the first time, asking for this name. */
    public JsonNode signUp(String name) throws Exception {
        return signInAs(UUID.randomUUID().toString(), name);
    }

    /** Signs in as this Supabase user, offering this name in case it is their first time. */
    public JsonNode signInAs(String person, String name) throws Exception {
        return postJson("/api/account/supabase", java.util.Map.of("accessToken", FakeIdentities.token(person, name)));
    }

    public JsonNode post(String path) throws Exception {
        return send(MockMvcRequestBuilders.post(path));
    }

    public JsonNode get(String path) throws Exception {
        return send(MockMvcRequestBuilders.get(path));
    }

    public JsonNode postJson(String path, Object body) throws Exception {
        return send(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    public int lastStatus() {
        return lastStatus;
    }

    /** Like a browser: whatever session cookie the server sets is sent from then on. */
    private void keepCookie(MockHttpServletResponse response) {
        String header = response.getHeader("Set-Cookie");
        if (header != null && header.startsWith(AccountService.COOKIE + "=")) {
            String value = header.substring(AccountService.COOKIE.length() + 1, header.indexOf(';'));
            session = new Cookie(AccountService.COOKIE, value);
        }
    }

    private JsonNode send(MockHttpServletRequestBuilder request) throws Exception {
        if (session != null) {
            request.cookie(session);
        }
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        lastStatus = response.getStatus();
        keepCookie(response);
        String body = response.getContentAsString();
        return mapper.readTree(body.isEmpty() ? "{}" : body);
    }
}
