package com.fj.direct;

import java.util.HashMap;
import java.util.Map;

/**
 * 角色索引 → 中文名。
 *
 * 两套键并存（与原 root 版 身份.h 完全一致，一张表搞定）：
 *   - 新版本单字节角色索引：45~52 / 101~118 / 201~209，0 = 狼
 *   - 旧版本长整型角色 ID（游戏改版前使用，保留以兼容 / 备查）
 */
public final class RoleTable {

    private static final Map<Integer, String> MAP = new HashMap<Integer, String>();

    private static void put(int id, String name) {
        MAP.put(Integer.valueOf(id), name);
    }

    static {
        // ---- 旧版本长整型角色 ID ----
        put(774232427, "银行家");
        put(774232430, "拳击手");
        put(774232434, "掮客");
        put(-651165235, "棋手");
        put(-651099699, "清洁工");
        put(635030476, "阴谋家");
        put(-651034163, "顾问");
        put(-651296307, "送货员");
        put(774232421, "侦探");
        put(635031756, "处刑人");
        put(635030220, "千面人");
        put(635030732, "烟火师");
        put(-651230771, "愚人");
        put(774232422, "哨兵");
        put(774232424, "猎人");
        put(635031244, "催眠师");
        put(-650968627, "降灵师");
        put(774232426, "锁匠");
        put(774232431, "灵媒");
        put(774232428, "修士");
        put(1, "普通人");
        put(774232429, "演说家");
        put(635030988, "怪盗");
        put(774232435, "药剂师");
        put(774232423, "治安官");
        put(774232436, "巡林员");
        put(774232425, "香料师");
        put(774232433, "密探");
        put(635031500, "地下医生");
        put(635029964, "神偷");
        put(774232432, "学徒");
        put(774232438, "评论家");
        put(-651361843, "流浪汉");
        put(774232437, "执灯人");
        put(635032012, "指挥家");
        put(650903091, "导演");

        // ---- 新版本单字节角色索引 ----
        put(102, "哨兵");
        put(104, "猎人");
        put(106, "锁匠");
        put(109, "演说家");
        put(113, "密探");
        put(115, "药剂师");
        put(117, "执灯人");
        put(45, "流浪汉");
        put(50, "顾问");
        put(202, "千面人");
        put(206, "催眠师");
        put(207, "地下医生");
        put(205, "怪盗");
        put(51, "降灵师");
        put(52, "导演");
        put(103, "治安官");
        put(108, "修士");
        put(111, "灵媒");
        put(114, "掮客");
        put(116, "巡林员");
        put(118, "评论家");
        put(201, "神偷");
        put(203, "阴谋家");
        put(48, "棋手");
        put(110, "拳击手");
        put(46, "送货员");
        put(101, "侦探");
        put(204, "烟火师");
        put(49, "清洁工");
        put(112, "学徒");
        put(105, "香料师");
        put(208, "处刑人");
        put(209, "指挥家");
        put(47, "愚人");
        put(107, "银行家");
        put(0, "狼");
    }

    private RoleTable() {
    }

    public static String nameOf(int roleId) {
        String s = MAP.get(Integer.valueOf(roleId));
        return s != null ? s : ("未知角色" + roleId);
    }

    public static String campName(int campId) {
        if (campId == 1) {
            return "侦探团";
        }
        if (campId == 2) {
            return "狼人";
        }
        return "神秘客";
    }
}
