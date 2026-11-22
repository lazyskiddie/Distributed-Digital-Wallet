package com.ledger.Wallet;

import com.ledger.Wallet.Model.LoginThrottle;
import com.ledger.Wallet.Repository.AccountRepository;
import com.ledger.Wallet.Repository.SessionRepository;
import com.ledger.Wallet.Repository.TransactionLogRepository;
import com.ledger.Wallet.Repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

// End-to-end tests of sign-up, sign-in, the wallet and its privacy rules, through the real HTTP layer
@SpringBootTest
class AuthWalletApiTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired private WebApplicationContext context;
    @Autowired private AccountRepository accounts;
    @Autowired private UserRepository users;
    @Autowired private SessionRepository sessions;
    @Autowired private TransactionLogRepository logs;
    @Autowired private LoginThrottle throttle;

    private MockMvc mvc;

    // A signed-in test user
    private record Session(Cookie cookie, String email, String wallet) {}

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        logs.deleteAll();
        sessions.deleteAll();
        accounts.deleteAll();
        users.deleteAll();
        throttle.clear();
    }

    /* ---------- helpers ---------- */

    private MvcResult post(String url, String json, Cookie cookie) throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON).content(json).header("X-Requested-With", "wallet");
        if (cookie != null) b.cookie(cookie);
        return mvc.perform(b).andReturn();
    }

    private MvcResult get(String url, Cookie cookie) throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.get(url);
        if (cookie != null) b.cookie(cookie);
        return mvc.perform(b).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), "pattern " + regex + " not found in: " + text);
        return m.group(1);
    }

    private static String registerJson(String name, String email, String password) {
        return "{\"fullName\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private Session register(String name, String email) throws Exception {
        MvcResult r = post("/api/v1/auth/register", registerJson(name, email, PASSWORD), null);
        assertEquals(201, status(r), body(r));
        Cookie cookie = r.getResponse().getCookie("WALLET_SESSION");
        assertNotNull(cookie, "sign-up must start a session");
        return new Session(cookie, email.toLowerCase(), find(body(r), "\"accountNumber\":\"(ACC-\\d+)\""));
    }

    private BigDecimal balanceOf(Session s) throws Exception {
        return new BigDecimal(find(body(get("/api/v1/me", s.cookie())), "\"balance\":([0-9.]+)"));
    }

    private MvcResult deposit(Session s, String amount) throws Exception {
        return post("/api/v1/me/deposit", "{\"amount\":" + amount + "}", s.cookie());
    }

    private MvcResult send(Session from, String recipient, String amount) throws Exception {
        return post("/api/v1/me/transfers", "{\"recipient\":\"" + recipient + "\",\"amount\":" + amount + "}", from.cookie());
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    /* ---------- sign-up ---------- */

    @Test
    void signUpCreatesAnEmptyWalletAndSignsTheUserIn() throws Exception {
        MvcResult r = post("/api/v1/auth/register", registerJson("Asha  Kapoor", "Asha.K@Example.com", PASSWORD), null);

        assertEquals(201, status(r));
        String b = body(r);
        assertTrue(b.contains("\"fullName\":\"Asha Kapoor\""), "extra spaces are tidied: " + b);
        assertTrue(b.contains("\"email\":\"asha.k@example.com\""), "email is stored lower-case: " + b);
        assertTrue(b.contains("ACC-"));
        assertFalse(b.toLowerCase().contains("password"), "the password must never be sent back");

        String setCookie = r.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Lax"), setCookie);

        Session s = new Session(r.getResponse().getCookie("WALLET_SESSION"), "asha.k@example.com", null);
        assertMoney("0", balanceOf(s));
    }

    @Test
    void passwordIsStoredHashedNotInPlainText() throws Exception {
        register("Asha Kapoor", "asha@example.com");
        String stored = users.findByEmail("asha@example.com").orElseThrow().getPasswordHash();
        assertTrue(stored.startsWith("pbkdf2_sha256$"));
        assertFalse(stored.contains(PASSWORD));
    }

    @Test
    void signUpRejectsAnEmailThatIsAlreadyUsedEvenInDifferentCase() throws Exception {
        register("Asha Kapoor", "asha@example.com");
        MvcResult r = post("/api/v1/auth/register", registerJson("Someone Else", "ASHA@example.com", PASSWORD), null);
        assertEquals(409, status(r));
        assertTrue(body(r).contains("already exists"));
        assertEquals(1, users.count());
    }

    @Test
    void signUpValidatesTheForm() throws Exception {
        MvcResult shortPassword = post("/api/v1/auth/register", registerJson("Asha Kapoor", "a@example.com", "short"), null);
        assertEquals(400, status(shortPassword));
        assertTrue(body(shortPassword).contains("Password must be 8"));

        MvcResult badEmail = post("/api/v1/auth/register", registerJson("Asha Kapoor", "not-an-email", PASSWORD), null);
        assertEquals(400, status(badEmail));
        assertTrue(body(badEmail).contains("valid email"));

        MvcResult noName = post("/api/v1/auth/register", registerJson("", "a@example.com", PASSWORD), null);
        assertEquals(400, status(noName));
        assertEquals(0, users.count());
    }

    /* ---------- sign-in / sign-out ---------- */

    @Test
    void signInWorksAndWrongCredentialsGetTheSameGenericAnswer() throws Exception {
        register("Asha Kapoor", "asha@example.com");

        MvcResult ok = post("/api/v1/auth/login", "{\"email\":\"ASHA@example.com\",\"password\":\"" + PASSWORD + "\"}", null);
        assertEquals(200, status(ok));
        assertNotNull(ok.getResponse().getCookie("WALLET_SESSION"));

        MvcResult wrongPassword = post("/api/v1/auth/login", "{\"email\":\"asha@example.com\",\"password\":\"nope-nope-nope\"}", null);
        MvcResult unknownEmail = post("/api/v1/auth/login", "{\"email\":\"ghost@example.com\",\"password\":\"nope-nope-nope\"}", null);
        assertEquals(401, status(wrongPassword));
        assertEquals(401, status(unknownEmail));
        assertTrue(body(wrongPassword).contains("Incorrect email or password."));
        assertTrue(body(unknownEmail).contains("Incorrect email or password."), "must not reveal which emails exist");
    }

    @Test
    void repeatedWrongPasswordsLockSignInTemporarily() throws Exception {
        register("Asha Kapoor", "asha@example.com");
        String wrong = "{\"email\":\"asha@example.com\",\"password\":\"nope-nope-nope\"}";
        for (int i = 0; i < 5; i++) {
            assertEquals(401, status(post("/api/v1/auth/login", wrong, null)));
        }
        assertEquals(429, status(post("/api/v1/auth/login", wrong, null)));
        String right = "{\"email\":\"asha@example.com\",\"password\":\"" + PASSWORD + "\"}";
        assertEquals(429, status(post("/api/v1/auth/login", right, null)), "even the right password waits");
    }

    @Test
    void signOutEndsTheSession() throws Exception {
        Session s = register("Asha Kapoor", "asha@example.com");
        assertEquals(200, status(get("/api/v1/me", s.cookie())));

        assertEquals(204, status(post("/api/v1/auth/logout", "", s.cookie())));
        assertEquals(401, status(get("/api/v1/me", s.cookie())), "the old cookie must stop working");
    }

    /* ---------- access control ---------- */

    @Test
    void walletEndpointsNeedASession() throws Exception {
        assertEquals(401, status(get("/api/v1/me", null)));
        assertEquals(401, status(get("/api/v1/me/transactions", null)));
        assertEquals(401, status(get("/api/v1/me", new Cookie("WALLET_SESSION", "forged-value"))));
        assertEquals(401, status(post("/api/v1/me/transfers", "{\"recipient\":\"x\",\"amount\":1}", null)));
    }

    @Test
    void requestsThatChangeDataNeedTheAntiForgeryHeader() throws Exception {
        Session s = register("Asha Kapoor", "asha@example.com");
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/me/deposit")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}").cookie(s.cookie())).andReturn();
        assertEquals(403, status(r));
        assertMoney("0", balanceOf(s));
    }

    @Test
    void theOldOpenEndpointsAreGone() throws Exception {
        Session s = register("Asha Kapoor", "asha@example.com");
        assertEquals(404, status(get("/api/v1/accounts", s.cookie())), "no list of everyone's wallets");
        int old = status(post("/api/v1/transfers", "{}", s.cookie()));
        assertTrue(old == 404 || old == 405, "the old transfer API must be gone, got " + old);
    }

    /* ---------- add money ---------- */

    @Test
    void addingMoneyRaisesTheBalanceAndAppearsInTheHistory() throws Exception {
        Session s = register("Asha Kapoor", "asha@example.com");
        MvcResult r = deposit(s, "500.50");

        assertEquals(200, status(r), body(r));
        assertMoney("500.50", balanceOf(s));
        String history = body(get("/api/v1/me/transactions", s.cookie()));
        assertTrue(history.contains("Added money") && history.contains("\"direction\":\"IN\""), history);
    }

    @Test
    void addMoneyRejectsBadAmounts() throws Exception {
        Session s = register("Asha Kapoor", "asha@example.com");
        assertEquals(400, status(deposit(s, "0")));
        assertEquals(400, status(deposit(s, "-5")));
        assertEquals(400, status(deposit(s, "100000.01")));
        assertEquals(400, status(deposit(s, "12.345")));
        assertMoney("0", balanceOf(s));
    }

    /* ---------- sending money ---------- */

    @Test
    void sendByEmailMovesMoneyBetweenTheTwoWallets() throws Exception {
        Session asha = register("Asha Kapoor", "asha@example.com");
        Session ravi = register("Ravi Menon", "ravi@example.com");
        deposit(asha, "1000");

        MvcResult r = send(asha, "RAVI@example.com", "250.25");

        assertEquals(200, status(r), body(r));
        assertTrue(body(r).contains("Ravi Menon"));
        assertMoney("749.75", balanceOf(asha));
        assertMoney("250.25", balanceOf(ravi));

        assertTrue(body(get("/api/v1/me/transactions", asha.cookie())).contains("\"direction\":\"OUT\""));
        String ravisHistory = body(get("/api/v1/me/transactions", ravi.cookie()));
        assertTrue(ravisHistory.contains("\"direction\":\"IN\"") && ravisHistory.contains("Asha Kapoor"), ravisHistory);
    }

    @Test
    void sendByWalletNumberWorksToo() throws Exception {
        Session asha = register("Asha Kapoor", "asha@example.com");
        Session ravi = register("Ravi Menon", "ravi@example.com");
        deposit(asha, "100");

        assertEquals(200, status(send(asha, ravi.wallet().toLowerCase(), "40")));
        assertMoney("60", balanceOf(asha));
        assertMoney("40", balanceOf(ravi));
    }

    @Test
    void sendingRejectsUnknownRecipientSelfInsufficientFundsAndBadAmounts() throws Exception {
        Session asha = register("Asha Kapoor", "asha@example.com");
        Session ravi = register("Ravi Menon", "ravi@example.com");
        deposit(asha, "100");

        MvcResult unknown = send(asha, "nobody@example.com", "10");
        assertEquals(400, status(unknown));
        assertTrue(body(unknown).contains("couldn't find"));

        MvcResult self = send(asha, "asha@example.com", "10");
        assertEquals(400, status(self));
        assertTrue(body(self).contains("yourself"));

        MvcResult poor = send(asha, ravi.email(), "100.01");
        assertEquals(400, status(poor));
        assertTrue(body(poor).contains("enough balance"));

        assertEquals(400, status(send(asha, ravi.email(), "0")));
        assertEquals(400, status(send(asha, ravi.email(), "1.234")));
        assertEquals(400, status(post("/api/v1/me/transfers", "{\"amount\":5}", asha.cookie())));

        assertMoney("100", balanceOf(asha));
        assertMoney("0", balanceOf(ravi));
    }

    @Test
    void aUserCannotSpendSomeoneElsesMoneyByNamingTheirWallet() throws Exception {
        Session asha = register("Asha Kapoor", "asha@example.com");
        Session ravi = register("Ravi Menon", "ravi@example.com");
        deposit(ravi, "1000");

        // Asha is broke and tries to smuggle Ravi's wallet in as the "source".
        post("/api/v1/me/transfers", "{\"recipient\":\"" + asha.email() + "\",\"amount\":10,\"sourceAccount\":\""
                + ravi.wallet() + "\"}", asha.cookie());

        assertMoney("1000", balanceOf(ravi));
        assertMoney("0", balanceOf(asha));
    }

    /* ---------- recipient check ---------- */

    @Test
    void recipientLookupShowsTheNameBeforeSending() throws Exception {
        Session asha = register("Asha Kapoor", "asha@example.com");
        Session ravi = register("Ravi Menon", "ravi@example.com");

        MvcResult byEmail = get("/api/v1/me/recipient?q=ravi@example.com", asha.cookie());
        assertEquals(200, status(byEmail));
        assertTrue(body(byEmail).contains("Ravi Menon") && body(byEmail).contains(ravi.wallet()));

        assertEquals(404, status(get("/api/v1/me/recipient?q=ghost@example.com", asha.cookie())));
        assertEquals(401, status(get("/api/v1/me/recipient?q=ravi@example.com", null)));
    }

    /* ---------- privacy ---------- */

    @Test
    void historyIsPrivateAndDeclinedAttemptsStayWithTheSender() throws Exception {
        Session asha = register("Asha Kapoor", "asha@example.com");
        Session ravi = register("Ravi Menon", "ravi@example.com");
        Session meera = register("Meera Shah", "meera@example.com");
        deposit(asha, "50");

        assertEquals(200, status(send(asha, ravi.email(), "20")));
        assertEquals(400, status(send(asha, ravi.email(), "5000"))); // declined: not enough money

        assertTrue(body(get("/api/v1/me/transactions", asha.cookie())).contains("FAILED"), "sender sees the declined attempt");
        String ravisHistory = body(get("/api/v1/me/transactions", ravi.cookie()));
        assertFalse(ravisHistory.contains("FAILED"), "recipient must not see failed attempts: " + ravisHistory);
        assertEquals("[]", body(get("/api/v1/me/transactions", meera.cookie())).trim(), "outsiders see nothing");
    }
}
