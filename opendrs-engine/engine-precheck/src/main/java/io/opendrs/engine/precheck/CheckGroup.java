package io.opendrs.engine.precheck;

/**
 * UI grouping. The code is stable (it is stored in the result JSON and compared by tests); the
 * label is the text the wizard prints and may be reworded without touching any id.
 */
public enum CheckGroup {
    PARAMETER("数据库参数检查", 10),
    INSTANCE("目标实例状态检查", 20),
    PERMISSION("数据库用户权限检查", 30),
    VERSION("数据库版本检查", 40),
    NETWORK("网络状态", 50),
    OBJECT("数据库对象检查", 60);

    private final String label;
    private final int order;

    CheckGroup(String label, int order) {
        this.label = label;
        this.order = order;
    }

    public String label() {
        return label;
    }

    public int order() {
        return order;
    }
}
