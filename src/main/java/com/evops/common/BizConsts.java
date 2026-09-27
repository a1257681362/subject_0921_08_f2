package com.evops.common;

/**
 * 业务常量：记录状态 / 单据状态 / 时段。
 */
public final class BizConsts {
    private BizConsts() {
    }

    /** 记录状态：正常 / 已冲销（物理删除一律禁止） */
    public static final String RECORD_NORMAL = "NORMAL";
    public static final String RECORD_VOIDED = "VOIDED";

    /** 人员状态 */
    public static final String STAFF_ACTIVE = "ACTIVE";
    public static final String STAFF_SUSPENDED = "SUSPENDED";

    /** 服务单状态：待派单 / 已派单 */
    public static final String ORDER_PENDING = "PENDING";
    public static final String ORDER_ASSIGNED = "ASSIGNED";

    /** 时段：上午 / 下午 / 全天（全天与任一时段冲突） */
    public static final String SLOT_AM = "AM";
    public static final String SLOT_PM = "PM";
    public static final String SLOT_ALLDAY = "ALLDAY";

    /** 明细冲销原因：随主单冲销 / 变更被替换 */
    public static final String VOID_BY_ORDER = "ORDER";
    public static final String VOID_BY_REPLACE = "REPLACE";
}
