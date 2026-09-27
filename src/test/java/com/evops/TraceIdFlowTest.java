package com.evops;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.evops.common.SensitiveMasker;
import com.evops.order.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 链路追踪：响应头回带 traceId、外部 traceId 接力、异步任务 MDC 接力、
 * 请求结束 MDC 清理、敏感字段打码、工单日志可按工单号检索。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraceIdFlowTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private TaskExecutor taskExecutor;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM t_request_record");
        jdbc.update("DELETE FROM t_operation_log");
        jdbc.update("DELETE FROM t_service_order_item");
        jdbc.update("DELETE FROM t_service_order");
        jdbc.update("DELETE FROM t_staff_leave");
        jdbc.update("DELETE FROM t_staff");
    }

    /** 响应头回带 traceId */
    @Test
    void responseHeaderCarriesTraceId() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"));
    }

    /** 上游已带 traceId 时原样接力，不重新生成 */
    @Test
    void incomingTraceIdIsRelayed() throws Exception {
        mvc.perform(get("/api/health").header("X-Trace-Id", "upstream-abc-123"))
                .andExpect(header().string("X-Trace-Id", "upstream-abc-123"));
    }

    /** 非法 traceId（注入字符）一律丢弃，换新 id */
    @Test
    void illegalIncomingTraceIdIsReplaced() throws Exception {
        MvcResult r = mvc.perform(get("/api/health").header("X-Trace-Id", "bad\r\ninjection"))
                .andReturn();
        String traceId = r.getResponse().getHeader("X-Trace-Id");
        assertNotNull(traceId);
        assertTrue(traceId.matches("[a-f0-9]{32}"), "非法 traceId 应替换为新生成的 32 位十六进制，实际：" + traceId);
    }

    /** 异步任务接力：提交线程的 traceId/orderNo 在执行线程可见 */
    @Test
    void asyncTaskRelaysMdc() throws Exception {
        MDC.put("traceId", "trace-async-1");
        MDC.put("orderNo", "WO-ASYNC-1");
        try {
            CompletableFuture<Map<String, String>> seen = new CompletableFuture<>();
            taskExecutor.execute(() -> {
                Map<String, String> m = new HashMap<>();
                m.put("traceId", MDC.get("traceId"));
                m.put("orderNo", MDC.get("orderNo"));
                seen.complete(m);
            });
            Map<String, String> m = seen.get(5, TimeUnit.SECONDS);
            assertEquals("trace-async-1", m.get("traceId"), "异步线程应接力 traceId");
            assertEquals("WO-ASYNC-1", m.get("orderNo"), "异步线程应接力 orderNo");
        } finally {
            MDC.clear();
        }
    }

    /** 请求结束后 MDC 必须清理，防线程复用串号 */
    @Test
    void mdcClearedAfterRequest() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        assertNull(MDC.getCopyOfContextMap(), "请求结束后 MDC 必须清理");
    }

    /** 敏感字段打码规则 */
    @Test
    void sensitiveFieldsMasked() {
        assertEquals("138****0000", SensitiveMasker.maskPhone("13800000000"));
        assertEquals("1**", SensitiveMasker.maskPhone("138"));
        assertEquals("*", SensitiveMasker.maskPhone("1"));
        assertEquals("张*", SensitiveMasker.maskName("张三"));
        assertEquals("王**", SensitiveMasker.maskName("王小明"));
        assertEquals("*", SensitiveMasker.maskName("张"));
        assertEquals("某小****", SensitiveMasker.maskAddress("某小区1号"));
        assertEquals("幸福里小区1****", SensitiveMasker.maskAddress("幸福里小区12栋3单元501室"));
        assertNull(SensitiveMasker.maskPhone(null));
        assertNull(SensitiveMasker.maskName(null));
        assertNull(SensitiveMasker.maskAddress(null));
    }

    /** 上门主链路：创建工单的日志携带 orderNo（可按工单号检索），电话打码不出现明文 */
    @Test
    void orderLogsCarryOrderNoAndMaskedPhone() throws Exception {
        ch.qos.logback.classic.Logger orderLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OrderService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        orderLogger.addAppender(appender);
        try {
            String body = "{\"requestNo\":\"REQ-T-1\",\"orderNo\":\"WO-9001\","
                    + "\"customerName\":\"张小三\",\"customerPhone\":\"13800001111\","
                    + "\"address\":\"幸福里小区12栋3单元501室\","
                    + "\"serviceDate\":\"2026-10-20\",\"timeSlot\":\"AM\",\"items\":["
                    + "{\"itemName\":\"日常保洁\",\"quantity\":1,\"price\":199}]}";
            mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(header().exists("X-Trace-Id"));
        } finally {
            orderLogger.detachAppender(appender);
        }

        assertFalse(appender.list.isEmpty(), "主链路应有业务日志");
        ILoggingEvent createEvent = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("WO-9001"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到带工单号的日志，无法按工单号检索"));
        assertEquals("WO-9001", createEvent.getMDCPropertyMap().get("orderNo"),
                "日志事件 MDC 应携带工单号");
        assertNotNull(createEvent.getMDCPropertyMap().get("traceId"), "日志事件 MDC 应携带 traceId");
        String msg = createEvent.getFormattedMessage();
        assertTrue(msg.contains("138****1111"), "电话应打码，实际：" + msg);
        assertFalse(msg.contains("13800001111"), "日志不得出现明文电话");
        assertFalse(msg.contains("张小三"), "日志不得出现明文客户姓名");
    }
}
