package com.wangziyang.mes.assistant;

import com.wangziyang.mes.system.dto.SysMenuDTO;
import com.wangziyang.mes.system.entity.SysMenu;
import com.wangziyang.mes.system.mapper.SysMenuMapper;
import com.wangziyang.mes.system.service.impl.SysMenuServiceImpl;
import com.wangziyang.mes.system.vo.TreeVO;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class SysMenuNavigationTest {
    private SysMenuMapper mapper;
    private SysMenuServiceImpl service;

    @Before
    public void setUp() throws Exception {
        mapper = mock(SysMenuMapper.class);
        service = new SysMenuServiceImpl();
        ReflectionTestUtils.setField(service, "sysMenuMapper", mapper);
        List<SysMenuDTO> menus = Arrays.asList(
                menu("root", "0", "0", ""),
                menu("route-menu", "root", "1", ""),
                menu("legacy-menu", "root", null, ""),
                menu("assistant-button", "0", "2", "mes:assistant:routing:read"),
                menu("edit-button", "route-menu", "2", "sys:menu:update"));
        List<SysMenu> records = new ArrayList<>(menus);
        when(mapper.selectList(any())).thenReturn(records);
        when(mapper.listBySearchByName(anyString())).thenReturn(records);
        when(mapper.listByRoleId("approved-role")).thenReturn(menus);
    }

    @Test
    public void initialNavigationOmitsRootAndChildButtonsWhilePreservingNormalAndNullTypes() throws Exception {
        assertNavigation(service.listIndexMenuTree());
    }

    @Test
    public void searchedNavigationOmitsRootAndChildButtonsWhilePreservingNormalAndNullTypes() throws Exception {
        assertNavigation(service.listIndexMenuSearchTree("工艺"));
        verify(mapper).listBySearchByName("工艺");
    }

    @Test
    public void roleAssignmentTreeStillIncludesButtonPermissions() throws Exception {
        List<TreeVO<SysMenu>> nodes = flatten(service.listMenuTree());
        assertEquals(ids("root", "route-menu", "legacy-menu", "assistant-button", "edit-button"), treeIds(nodes));
        assertTrue(nodes.stream().anyMatch(node -> "2".equals(node.getType())
                && "mes:assistant:routing:read".equals(node.getPermission())));
    }

    @Test
    public void roleAuthorizationListStillIncludesButtonPermissions() throws Exception {
        List<SysMenuDTO> menus = service.listByRoleId("approved-role");
        Set<String> actualIds = new LinkedHashSet<>();
        for (SysMenuDTO menu : menus) {
            actualIds.add(menu.getId());
        }
        assertEquals(ids("root", "route-menu", "legacy-menu", "assistant-button", "edit-button"), actualIds);
        assertTrue(menus.stream().anyMatch(menu -> "2".equals(menu.getType())
                && "mes:assistant:routing:read".equals(menu.getPermission())));
    }

    @SuppressWarnings("unchecked")
    private void assertNavigation(Map<String, Object> result) {
        Map<String, TreeVO<SysMenu>> roots = (Map<String, TreeVO<SysMenu>>) result.get("menuInfo");
        assertEquals(ids("root"), roots.keySet());
        List<TreeVO<SysMenu>> nodes = flatten(roots.values());
        assertEquals(ids("root", "route-menu", "legacy-menu"), treeIds(nodes));
        assertTrue(nodes.stream().noneMatch(node -> "2".equals(node.getType())));
    }

    private List<TreeVO<SysMenu>> flatten(Collection<TreeVO<SysMenu>> nodes) {
        List<TreeVO<SysMenu>> result = new ArrayList<>();
        for (TreeVO<SysMenu> node : nodes) {
            result.add(node);
            result.addAll(flatten(node.getChildren()));
        }
        return result;
    }

    private Set<String> treeIds(List<TreeVO<SysMenu>> nodes) {
        Set<String> result = new LinkedHashSet<>();
        for (TreeVO<SysMenu> node : nodes) {
            result.add(node.getId());
        }
        return result;
    }

    private Set<String> ids(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private SysMenuDTO menu(String id, String parentId, String type, String permission) {
        SysMenuDTO menu = new SysMenuDTO();
        menu.setId(id);
        menu.setCode(id);
        menu.setName(id);
        menu.setParentId(parentId);
        menu.setType(type);
        menu.setPermission(permission);
        return menu;
    }
}
