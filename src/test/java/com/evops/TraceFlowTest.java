package com.evops;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.evops.common.Masks;
import com.evops.common.log.MaskingMessageConverter;
import com.evops.common.trace.TraceContext;
import com.evops.system.OperationLog;
import com.evops.system.OperationLogMapper;
import com.evops.system.TraceRecord;
import com.evops.system.TraceRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F2 全链路追踪回归：聚焦上门主链路（建单→派单→改期→冲销→恢复）。
 * 不开测试事务：调用轨迹异步落库，用轮询等待落库完成。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraceFlowTest {
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TraceRecordMapper traceRecordMapper;
    @Autowired
    private OperationLogMapper logMapper;
    @Autowired
    @Qualifier("traceTaskExecutor")
    private ThreadPoolTaskExecutor traceTaskExecutor;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM t_trace_record");
        jdbc.update("DELETE FROM t_request_record");
        jdbc.update("DELETE FROM t_operation_log");
        jdbc.update("DELETE FROM t_service_order_item");
        jdbc.update("DELETE FROM t_service_order");
        jdbc.update("DELETE FROM t_staff_leave");
        jdbc.update("DELETE FROM t_staff");
    }

    /** 响应头回带 TraceId；上游透传的 TraceId 原样接力 */
    @Test
    void traceId_responseHeaderEchoed_andIncomingHonored() throws Exception {
        mvc.perform(post("/api/orders").header(TraceContext.TRACE_HEADER, "trace-upstream-001")
                        .contentType(JSON).content(orderJson("REQ-T-H1", "WO-T-H1", "2026-11-08", "AM")))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceContext.TRACE_HEADER, "trace-upstream-001"));

        mvc.perform(get("/api/orders").param("date", "2026-11-08"))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceContext.TRACE_HEADER));
    }

    /** 上门主链路：按工单号检索出完整调用时序，traceId 与响应头一一对应，操作留痕携带 traceId */
    @Test
    void orderMainFlow_timelineByOrderNoShowsFullCallSequence() throws Exception {
        long staffId = createStaff("REQ-T-1", "S901", "周九");
        String orderNo = "WO-T-1001";
        List<String> traceIds = new ArrayList<>();

        MvcResult created = mvc.perform(post("/api/orders").contentType(JSON)
                        .content(orderJson("REQ-T-2", orderNo, "2026-11-01", "AM")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(header().exists(TraceContext.TRACE_HEADER)).andReturn();
        traceIds.add(created.getResponse().getHeader(TraceContext.TRACE_HEADER));
        long orderId = om.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        traceIds.add(postAndGetTraceId("/api/orders/" + orderId + "/assign",
                "{\"requestNo\":\"REQ-T-3\",\"staffId\":" + staffId + "}"));
        traceIds.add(postAndGetTraceId("/api/orders/" + orderId + "/reschedule",
                "{\"requestNo\":\"REQ-T-4\",\"serviceDate\":\"2026-11-02\"}"));
        traceIds.add(postAndGetTraceId("/api/orders/" + orderId + "/void",
                "{\"requestNo\":\"REQ-T-5\"}"));
        traceIds.add(postAndGetTraceId("/api/orders/" + orderId + "/restore",
                "{\"requestNo\":\"REQ-T-6\"}"));

        // 调用轨迹：5 次调用按序落库，traceId 与响应头一致，全部成功
        List<TraceRecord> timeline = awaitTimeline(orderNo, 5);
        assertEquals(5, timeline.size(), "主链路 5 次调用应全部落轨迹");
        List<String> expectedUris = Arrays.asList(
                "/api/orders",
                "/api/orders/" + orderId + "/assign",
                "/api/orders/" + orderId + "/reschedule",
                "/api/orders/" + orderId + "/void",
                "/api/orders/" + orderId + "/restore");
        assertEquals(expectedUris, timeline.stream().map(TraceRecord::getUri).collect(Collectors.toList()));
        for (int i = 0; i < timeline.size(); i++) {
            TraceRecord record = timeline.get(i);
            assertEquals(traceIds.get(i), record.getTraceId(), "第 " + i + " 次调用的 traceId 应与响应头一致");
            assertEquals(orderNo, record.getOrderNo());
            assertEquals("POST", record.getHttpMethod());
            assertTrue(record.getSuccess(), record.getUri() + " 应记为成功");
            assertNotNull(record.getCostMs());
        }

        // 操作留痕：每个动作都携带当次 traceId，可回查日志
        List<OperationLog> logs = logMapper.selectList(Wrappers.<OperationLog>lambdaQuery()
                .eq(OperationLog::getBizType, "ORDER")
                .eq(OperationLog::getBizId, orderId)
                .orderByAsc(OperationLog::getId));
        assertEquals(5, logs.size());
        for (OperationLog operationLog : logs) {
            assertNotNull(operationLog.getTraceId(), "操作留痕应携带 traceId：" + operationLog.getAction());
            assertTrue(traceIds.contains(operationLog.getTraceId()));
        }

        // 检索接口：按工单号拉出完整调用时序
        mvc.perform(get("/api/trace/orders/" + orderNo))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].uri").value("/api/orders"))
                .andExpect(jsonPath("$.data[0].traceId").value(traceIds.get(0)));
    }

    /** 失败调用同样落轨迹：success=false 并带失败原因 */
    @Test
    void failedCall_markedInTimeline() throws Exception {
        String orderNo = "WO-T-FAIL";
        mvc.perform(post("/api/orders").contentType(JSON)
                        .content(orderJson("REQ-T-F1", orderNo, "2026-11-05", "AM")))
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/api/orders").contentType(JSON)
                        .content(orderJson("REQ-T-F2", orderNo, "2026-11-06", "PM")))
                .andExpect(jsonPath("$.success").value(false));

        List<TraceRecord> timeline = awaitTimeline(orderNo, 2);
        assertEquals(2, timeline.size());
        assertTrue(timeline.get(0).getSuccess());
        assertFalse(timeline.get(1).getSuccess(), "重复工单号应记为失败调用");
        assertTrue(timeline.get(1).getErrorMsg().contains("工单号已存在"),
                "失败原因应落轨迹，实际：" + timeline.get(1).getErrorMsg());
    }

    /** 异步任务接力：提交到线程池的任务自动带上提交时刻的 TraceContext */
    @Test
    void asyncTask_relaysTraceContext() throws Exception {
        TraceContext.init("trace-relay-0001");
        TraceContext.bindOrderNo("WO-RELAY");
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> seen = new AtomicReference<>();
            traceTaskExecutor.execute(() -> {
                seen.set(MDC.get(TraceContext.TRACE_ID) + "/" + MDC.get(TraceContext.ORDER_NO));
                latch.countDown();
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS), "异步任务应按时执行");
            assertEquals("trace-relay-0001/WO-RELAY", seen.get(), "异步线程应接力 TraceContext");
        } finally {
            TraceContext.clear();
        }
    }

    /** 敏感字段入日志前打码：工具与 logback 转换器双重验证 */
    @Test
    void sensitiveFields_maskedBeforeLogging() {
        assertEquals("客户电话138****5678", Masks.maskText("客户电话13812345678"));
        assertEquals("证件1101**********123X", Masks.maskText("证件11010119900307123X"));
        assertEquals("工单WO-1001", Masks.maskText("工单WO-1001"), "普通单号不应被误打码");
        assertEquals("138****0000", Masks.maskPhone("13800000000"));
        assertEquals("张**", Masks.maskName("张小三"));

        LoggerContext loggerContext = new LoggerContext();
        ch.qos.logback.classic.Logger logger = loggerContext.getLogger("mask-test");
        LoggingEvent event = new LoggingEvent(MaskingMessageConverter.class.getName(), logger,
                Level.INFO, "上门联系人电话13812345678，请尽快确认", null, null);
        String line = new MaskingMessageConverter().convert(event);
        assertTrue(line.contains("138****5678"), "日志输出应打码，实际：" + line);
        assertFalse(line.contains("13812345678"), "日志输出不得出现明文手机号");
    }

    // ---------- 辅助 ----------

    private String orderJson(String requestNo, String orderNo, String date, String slot) {
        return "{\"requestNo\":\"" + requestNo + "\",\"orderNo\":\"" + orderNo + "\","
                + "\"customerName\":\"客户\",\"customerPhone\":\"13812345678\",\"address\":\"某小区1号\","
                + "\"serviceDate\":\"" + date + "\",\"timeSlot\":\"" + slot + "\",\"items\":["
                + "{\"itemName\":\"日常保洁\",\"quantity\":1,\"price\":199}]}";
    }

    private long createStaff(String requestNo, String staffNo, String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/staff").contentType(JSON)
                        .content("{\"requestNo\":\"" + requestNo + "\",\"staffNo\":\"" + staffNo
                                + "\",\"name\":\"" + name + "\"}"))
                .andExpect(jsonPath("$.success").value(true)).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String postAndGetTraceId(String uri, String body) throws Exception {
        MvcResult r = mvc.perform(post(uri).contentType(JSON).content(body))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(header().exists(TraceContext.TRACE_HEADER)).andReturn();
        return r.getResponse().getHeader(TraceContext.TRACE_HEADER);
    }

    /** 调用轨迹异步落库：轮询等待达到预期条数 */
    private List<TraceRecord> awaitTimeline(String orderNo, int expected) throws InterruptedException {
        List<TraceRecord> timeline = Collections.emptyList();
        for (int i = 0; i < 50; i++) {
            timeline = traceRecordMapper.selectList(Wrappers.<TraceRecord>lambdaQuery()
                    .eq(TraceRecord::getOrderNo, orderNo)
                    .orderByAsc(TraceRecord::getId));
            if (timeline.size() >= expected) {
                break;
            }
            Thread.sleep(100L);
        }
        return timeline;
    }
}
