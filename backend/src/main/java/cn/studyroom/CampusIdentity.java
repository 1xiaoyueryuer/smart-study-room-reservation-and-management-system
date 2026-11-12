package cn.studyroom;

import java.time.LocalDate;

/** 校园编号/登录名的唯一规则来源。 */
public final class CampusIdentity {
    private CampusIdentity() {}

    public static String validationError(String campusId, String role, int currentYear) {
        if ("admin".equals(role)) return null;
        if (campusId == null || !campusId.matches("[0-9]{4}[0-3][0-9]{4}"))
            return "校园编号须为9位：4位入学年份+1位身份类别+4位序号";
        int year = Integer.parseInt(campusId.substring(0, 4));
        if (year < 2000 || year > currentYear + 1)
            return "校园编号入学年份不合理";
        char category = campusId.charAt(4);
        if ("student".equals(role) && category >= '0' && category <= '2') return null;
        if ("staff".equals(role) && category == '3') return null;
        return "学生第5位须为0/1/2，教职工第5位须为3";
    }

    public static String validationError(String campusId, String role) {
        return validationError(campusId, role, LocalDate.now(BookingPolicy.CAMPUS_ZONE).getYear());
    }
}
