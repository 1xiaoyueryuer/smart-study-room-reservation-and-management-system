package cn.studyroom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CampusIdentityTest {
    @Test void acceptsOnlyNineDigitYearAndRoleCombinations() {
        assertNull(CampusIdentity.validationError("202600001","student",2026));
        assertNull(CampusIdentity.validationError("202610001","student",2026));
        assertNull(CampusIdentity.validationError("202620001","student",2026));
        assertNull(CampusIdentity.validationError("202630001","staff",2026));
        assertNull(CampusIdentity.validationError("202630001","admin",2026));
        assertTrue(CampusIdentity.validationError("202630001","student",2026).contains("第5位"));
        assertTrue(CampusIdentity.validationError("202600001","staff",2026).contains("第5位"));
        assertTrue(CampusIdentity.validationError("2028x0001","student",2026).contains("9位"));
    }

    @Test void boundsEntryYearWithoutAssumingAnInstitutionSpecificPrefix() {
        assertNull(CampusIdentity.validationError("200000001","student",2026));
        assertNull(CampusIdentity.validationError("202700001","student",2026));
        assertTrue(CampusIdentity.validationError("199900001","student",2026).contains("年份"));
        assertTrue(CampusIdentity.validationError("202800001","student",2026).contains("年份"));
    }
}
