package com.evops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.evops.order.ServiceOrder;
import com.evops.order.ServiceOrderItem;
import com.evops.order.ServiceOrderItemMapper;
import com.evops.order.ServiceOrderMapper;
import com.evops.staff.Staff;
import com.evops.staff.StaffMapper;
import com.evops.system.OperationLog;
import com.evops.system.OperationLogMapper;
import com.evops.system.RequestRecord;
import com.evops.system.RequestRecordMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 家政派单核心链路集成测试。
 * 不开测试事务：每个请求走真实事务提交，用例间逐表清理，互不影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HousekeepingFlowTest {
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StaffMapper staffMapper;
    @Autowired
    private ServiceOrderMapper orderMapper;
    @Autowired
    private ServiceOrderItemMapper itemMapper;
    @Autowired
    private OperationLogMapper logMapper;
    @Autowired
    private RequestRecordMapper requestRecordMapper;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM t_request_record");
        jdbc.update("DELETE FROM t_operation_log");
        jdbc.update("DELETE FROM t_service_order_item");
        jdbc.update("DELETE FROM t_service_order");
        jdbc.update("DELETE FROM t_staff_leave");
        jdbc.update("DELETE FROM t_staff");
    }

    /** 双击保存：同一请求号只落一条，重复请求返回首次结果 */
    @Test
    void createStaff_idempotent_doubleClickOnlyOneRow() throws Exception {
        String body = "{\"requestNo\":\"REQ-S-1\",\"staffNo\":\"S001\",\"name\":\"张三\",\"phone\":\"13800000000\"}";
        MvcResult r1 = mvc.perform(post("/api/staff").contentType(JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
        MvcResult r2 = mvc.perform(post("/api/staff").contentType(JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();

        JsonNode d1 = om.readTree(r1.getResponse().getContentAsString()).path("data");
        JsonNode d2 = om.readTree(r2.getResponse().getContentAsString()).path("data");
        assertEquals(d1.path("id").asLong(), d2.path("id").asLong(), "重复请求应返回首次结果");
        assertEquals(1L, staffMapper.selectCount(Wrappers.<Staff>lambdaQuery()
                .eq(Staff::getStaffNo, "S001")), "双击只应落一条人员记录");
        assertEquals(1L, requestRecordMapper.selectCount(Wrappers.<RequestRecord>lambdaQuery()
                .eq(RequestRecord::getRequestNo, "REQ-S-1")));
    }

    /** 服务单创建：主明细同事务落库；重复提交返回首次结果 */
    @Test
    void createOrder_masterDetailAtomic_andReplayReturnsFirst() throws Exception {
        String body = orderJson("REQ-O-1", "WO-1001", "2026-10-01", "AM");
        MvcResult r1 = mvc.perform(post("/api/orders").contentType(JSON).content(body))
                .andExpect(jsonPath("$.success").value(true)).andReturn();
        MvcResult r2 = mvc.perform(post("/api/orders").contentType(JSON).content(body))
                .andExpect(jsonPath("$.success").value(true)).andReturn();

        long id1 = om.readTree(r1.getResponse().getContentAsString()).path("data").path("id").asLong();
        long id2 = om.readTree(r2.getResponse().getContentAsString()).path("data").path("id").asLong();
        assertEquals(id1, id2);
        assertEquals(1L, orderMapper.selectCount(Wrappers.<ServiceOrder>lambdaQuery()
                .eq(ServiceOrder::getOrderNo, "WO-1001")));
        assertEquals(2L, itemMapper.selectCount(Wrappers.<ServiceOrderItem>lambdaQuery()
                .eq(ServiceOrderItem::getOrderId, id1)
                .eq(ServiceOrderItem::getRecordStatus, "NORMAL")), "主表落库时两条明细应一并落库");
    }

    /** 工单号唯一约束：不同请求号、相同工单号必须拒绝 */
    @Test
    void createOrder_duplicateOrderNoRejected() throws Exception {
        mvc.perform(post("/api/orders").contentType(JSON).content(orderJson("REQ-O-2", "WO-DUP", "2026-10-01", "AM")))
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/api/orders").contentType(JSON).content(orderJson("REQ-O-3", "WO-DUP", "2026-10-02", "PM")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("工单号已存在")));
    }

    /** 主明细同生共死：明细落库失败时主表不得半截落库 */
    @Test
    void createOrder_itemFailureRollsBackMaster() throws Exception {
        String longName = new String(new char[100]).replace('\0', '洗');
        String body = "{\"requestNo\":\"REQ-O-4\",\"orderNo\":\"WO-FAIL\",\"customerName\":\"李四\","
                + "\"serviceDate\":\"2026-10-01\",\"timeSlot\":\"AM\",\"items\":["
                + "{\"itemName\":\"" + longName + "\",\"quantity\":1,\"price\":100}]}";
        mvc.perform(post("/api/orders").contentType(JSON).content(body))
                .andExpect(jsonPath("$.success").value(false));
        assertEquals(0L, orderMapper.selectCount(Wrappers.<ServiceOrder>lambdaQuery()
                .eq(ServiceOrder::getOrderNo, "WO-FAIL")), "明细失败时主表不允许半截落库");
    }

    /** 派单避开休假与既有排班 */
    @Test
    void assign_avoidsLeaveAndScheduleConflict() throws Exception {
        long staffId = createStaff("REQ-A-1", "S101", "王五");
        addLeave(staffId, "REQ-A-2", "2026-10-02");

        long o1 = createOrder("REQ-A-3", "WO-2001", "2026-10-02", "AM");
        assignExpectFail(o1, staffId, "REQ-A-4", "休假");

        long o2 = createOrder("REQ-A-5", "WO-2002", "2026-10-03", "AM");
        assignExpectOk(o2, staffId, "REQ-A-6");

        long o3 = createOrder("REQ-A-7", "WO-2003", "2026-10-03", "AM");
        assignExpectFail(o3, staffId, "REQ-A-8", "已有排班");

        long o4 = createOrder("REQ-A-9", "WO-2004", "2026-10-03", "PM");
        assignExpectOk(o4, staffId, "REQ-A-10");
    }

    /** 改期：目标日期与休假冲突时自动顺延到最近可用日期 */
    @Test
    void reschedule_autoPostponeWhenConflict() throws Exception {
        long staffId = createStaff("REQ-R-1", "S102", "赵六");
        addLeave(staffId, "REQ-R-2", "2026-10-05");
        long orderId = createOrder("REQ-R-3", "WO-3001", "2026-10-04", "AM");
        assignExpectOk(orderId, staffId, "REQ-R-4");

        mvc.perform(post("/api/orders/" + orderId + "/reschedule").contentType(JSON)
                        .content("{\"requestNo\":\"REQ-R-5\",\"serviceDate\":\"2026-10-05\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.serviceDate").value("2026-10-06"));

        Long logs = logMapper.selectCount(Wrappers.<OperationLog>lambdaQuery()
                .eq(OperationLog::getBizType, "ORDER")
                .eq(OperationLog::getBizId, orderId)
                .eq(OperationLog::getAction, "RESCHEDULE")
                .like(OperationLog::getDetail, "顺延"));
        assertEquals(1L, logs, "自动顺延应留操作记录");
    }

    /** 冲销与恢复：状态翻转 + 明细随主 + 操作留痕 */
    @Test
    void voidAndRestore_leaveTrailAndItemsFollow() throws Exception {
        long orderId = createOrder("REQ-V-1", "WO-4001", "2026-10-08", "AM");

        mvc.perform(post("/api/orders/" + orderId + "/void").contentType(JSON)
                        .content("{\"requestNo\":\"REQ-V-2\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recordStatus").value("VOIDED"));
        assertEquals(0L, itemMapper.selectCount(Wrappers.<ServiceOrderItem>lambdaQuery()
                .eq(ServiceOrderItem::getOrderId, orderId)
                .eq(ServiceOrderItem::getRecordStatus, "NORMAL")), "冲销后明细应随主单一并冲销");
        assertEquals(1L, logMapper.selectCount(Wrappers.<OperationLog>lambdaQuery()
                .eq(OperationLog::getBizId, orderId)
                .eq(OperationLog::getAction, "VOID")
                .eq(OperationLog::getRequestNo, "REQ-V-2")), "冲销必须留操作记录");

        mvc.perform(post("/api/orders/" + orderId + "/restore").contentType(JSON)
                        .content("{\"requestNo\":\"REQ-V-3\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recordStatus").value("NORMAL"));
        assertEquals(2L, itemMapper.selectCount(Wrappers.<ServiceOrderItem>lambdaQuery()
                .eq(ServiceOrderItem::getOrderId, orderId)
                .eq(ServiceOrderItem::getRecordStatus, "NORMAL")), "恢复后明细应随主单一并恢复");
        assertEquals(1L, logMapper.selectCount(Wrappers.<OperationLog>lambdaQuery()
                .eq(OperationLog::getBizId, orderId)
                .eq(OperationLog::getAction, "RESTORE")
                .eq(OperationLog::getRequestNo, "REQ-V-3")), "恢复必须留操作记录");
    }

    /** 变更必须携带版本号，版本不符拒绝 */
    @Test
    void update_versionConflictRejected() throws Exception {
        long staffId = createStaff("REQ-M-1", "S103", "钱七");
        Staff current = staffMapper.selectById(staffId);

        String okBody = "{\"requestNo\":\"REQ-M-2\",\"staffNo\":\"S103\",\"name\":\"钱七\",\"phone\":\"139\","
                + "\"version\":" + current.getVersion() + "}";
        mvc.perform(put("/api/staff/" + staffId).contentType(JSON).content(okBody))
                .andExpect(jsonPath("$.success").value(true));

        String staleBody = "{\"requestNo\":\"REQ-M-3\",\"staffNo\":\"S103\",\"name\":\"钱七改\",\"phone\":\"139\","
                + "\"version\":" + current.getVersion() + "}";
        mvc.perform(put("/api/staff/" + staffId).contentType(JSON).content(staleBody))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已被他人变更")));
    }

    /** 组合检索：工单号 + 日期 */
    @Test
    void search_byOrderNoAndDate() throws Exception {
        createOrder("REQ-Q-1", "WO-5001", "2026-10-10", "AM");
        createOrder("REQ-Q-2", "WO-5002", "2026-10-10", "PM");

        mvc.perform(get("/api/orders/search").param("orderNo", "WO-5001").param("date", "2026-10-10"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderNo").value("WO-5001"))
                .andExpect(jsonPath("$.data[0].items.length()").value(2));

        mvc.perform(get("/api/orders/search").param("date", "2026-10-10"))
                .andExpect(jsonPath("$.data.length()").value(2));

        mvc.perform(get("/api/orders/search"))
                .andExpect(jsonPath("$.success").value(false));
    }

    // ---------- 辅助 ----------

    private String orderJson(String requestNo, String orderNo, String date, String slot) {
        return "{\"requestNo\":\"" + requestNo + "\",\"orderNo\":\"" + orderNo + "\","
                + "\"customerName\":\"客户\",\"customerPhone\":\"138\",\"address\":\"某小区1号\","
                + "\"serviceDate\":\"" + date + "\",\"timeSlot\":\"" + slot + "\",\"items\":["
                + "{\"itemName\":\"日常保洁\",\"quantity\":1,\"price\":199},"
                + "{\"itemName\":\"玻璃清洗\",\"quantity\":2,\"price\":50}]}";
    }

    private long createStaff(String requestNo, String staffNo, String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/staff").contentType(JSON)
                        .content("{\"requestNo\":\"" + requestNo + "\",\"staffNo\":\"" + staffNo
                                + "\",\"name\":\"" + name + "\"}"))
                .andExpect(jsonPath("$.success").value(true)).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private void addLeave(long staffId, String requestNo, String date) throws Exception {
        mvc.perform(post("/api/staff/" + staffId + "/leaves").contentType(JSON)
                        .content("{\"requestNo\":\"" + requestNo + "\",\"leaveDate\":\"" + date + "\"}"))
                .andExpect(jsonPath("$.success").value(true));
    }

    private long createOrder(String requestNo, String orderNo, String date, String slot) throws Exception {
        MvcResult r = mvc.perform(post("/api/orders").contentType(JSON)
                        .content(orderJson(requestNo, orderNo, date, slot)))
                .andExpect(jsonPath("$.success").value(true)).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private void assignExpectOk(long orderId, long staffId, String requestNo) throws Exception {
        mvc.perform(post("/api/orders/" + orderId + "/assign").contentType(JSON)
                        .content("{\"requestNo\":\"" + requestNo + "\",\"staffId\":" + staffId + "}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"));
    }

    private void assignExpectFail(long orderId, long staffId, String requestNo, String reason) throws Exception {
        MvcResult r = mvc.perform(post("/api/orders/" + orderId + "/assign").contentType(JSON)
                        .content("{\"requestNo\":\"" + requestNo + "\",\"staffId\":" + staffId + "}"))
                .andExpect(jsonPath("$.success").value(false)).andReturn();
        String message = om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("message").asText();
        assertTrue(message.contains(reason), "拒绝原因应包含「" + reason + "」，实际：" + message);
    }
}
