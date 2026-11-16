package com.ledger.Wallet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// HTTP-level tests. Raw JSON strings keep this independent of the JSON library version.
@SpringBootTest
class TransferControllerTest {

    private static final String URL = "/api/v1/transfers";

    @Autowired
    private WebApplicationContext context;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionLogRepository logRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        logRepository.deleteAll();
        accountRepository.deleteAll();
        accountRepository.save(Account.builder().accountNumber("ACC-001").balance(new BigDecimal("1000")).build());
        accountRepository.save(Account.builder().accountNumber("ACC-002").balance(new BigDecimal("1000")).build());
    }

    private MvcResult send(String json) throws Exception {
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    @Test
    void validTransferReturns200WithReference() throws Exception {
        MvcResult r = send("{\"sourceAccount\":\"ACC-001\",\"destinationAccount\":\"ACC-002\",\"amount\":10.00}");
        assertEquals(200, r.getResponse().getStatus());
        assertTrue(body(r).contains("SUCCESS"));
        assertTrue(body(r).contains("transactionReference"));
    }

    @Test
    void missingFieldsReturn400WithFieldDetails() throws Exception {
        MvcResult r = send("{}");
        assertEquals(400, r.getResponse().getStatus());
        String b = body(r);
        assertTrue(b.contains("Validation Failed"));
        assertTrue(b.contains("Source account is required"));
        assertTrue(b.contains("Destination account is required"));
        assertTrue(b.contains("Amount is required"));
    }

    @Test
    void zeroAmountReturns400() throws Exception {
        MvcResult r = send("{\"sourceAccount\":\"ACC-001\",\"destinationAccount\":\"ACC-002\",\"amount\":0}");
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(body(r).contains("greater than zero"));
    }

    @Test
    void negativeAmountReturns400() throws Exception {
        MvcResult r = send("{\"sourceAccount\":\"ACC-001\",\"destinationAccount\":\"ACC-002\",\"amount\":-5}");
        assertEquals(400, r.getResponse().getStatus());
    }

    @Test
    void insufficientFundsReturns400() throws Exception {
        MvcResult r = send("{\"sourceAccount\":\"ACC-001\",\"destinationAccount\":\"ACC-002\",\"amount\":5000}");
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(body(r).contains("Insufficient funds"));
    }

    @Test
    void unknownAccountReturns400() throws Exception {
        MvcResult r = send("{\"sourceAccount\":\"GHOST\",\"destinationAccount\":\"ACC-002\",\"amount\":1}");
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(body(r).contains("Account not found"));
    }

    @Test
    void malformedJsonReturns400NotServerError() throws Exception {
        MvcResult r = send("{ this is not json");
        assertEquals(400, r.getResponse().getStatus());
    }

    @Test
    void nonNumericAmountReturns400() throws Exception {
        MvcResult r = send("{\"sourceAccount\":\"ACC-001\",\"destinationAccount\":\"ACC-002\",\"amount\":\"abc\"}");
        assertEquals(400, r.getResponse().getStatus());
    }
}

