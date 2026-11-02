package com.wangziyang.mes.manufacturing;

import org.junit.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.Assert.*;

public class BusinessQueryTest {
    @Test public void defaultsAndLiteralLikeCharactersAreBounded() {
        BusinessQuery query = BusinessQuery.from(Collections.emptyMap());
        assertEquals(1, query.page); assertEquals(20, query.pageSize); assertEquals(0, query.offset());
        assertEquals("0", query.materialStatus);
        assertEquals("%50!%破损!_!!%", BusinessQuery.like("50%破损_!"));
        query = BusinessQuery.from(params("page", "3", "pageSize", "5", "materialName", "  控制器  "));
        assertEquals(10, query.offset()); assertEquals("控制器", query.materialName);
        assertEquals(1, query.withPage(1, 2).page); assertEquals(2, query.withPage(1, 2).pageSize);
        assertEquals(3, query.page);
    }

    @Test public void datesUseExplicitInclusiveStartAndExclusiveEndInLocalTime() {
        BusinessQuery query = BusinessQuery.from(params("createdFrom", "2026-11-01", "createdTo", "2026-11-02T12:30:00", "state", "CREATED"));
        assertEquals(LocalDateTime.of(2026,11,1,0,0), query.createdFrom);
        assertEquals(LocalDateTime.of(2026,11,2,12,30), query.createdTo);
        assertEquals("CREATED", query.state);
    }

    @Test public void invalidFiltersFailBeforeAnyDatabaseAccess() {
        for (Map<String,String> input : Arrays.asList(
                params("page", "0"), params("pageSize", "51"), params("page", "1001"), params("page", "1.2"),
                params("materialStatus", "3"), params("state", "RELEASED"), params("createdFrom", "2026-02-30"),
                params("createdFrom", "2026-11-02", "createdTo", "2026-11-01"),
                params("createdTo", "2026-11-02T00:00:00Z"), params("materialCode", "x' OR 1=1"),
                params("materialCode", "M-1\u0000"), params("operationCode","\u0000OP-1"),
                params("operationName", "测试\u0000"), params("sql", "SELECT 1"))) {
            try { BusinessQuery.from(input); fail("Invalid filters accepted: " + input.keySet()); }
            catch (BusinessException expected) { assertEquals("INVALID_INPUT", expected.getStatus()); }
        }
    }

    public static Map<String,String> params(String... values) {
        Map<String,String> result = new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2) result.put(values[i],values[i+1]);
        return result;
    }
}
