-- Knowledge read permission: select each approved role explicitly in this session.
-- SET @assistant_knowledge_role_id = 'your-approved-public-knowledge-role';
-- Optional, separate grant for restricted documents (never implied by public):
-- SET @assistant_restricted_role_id = 'your-approved-restricted-knowledge-role';
-- SOURCE scripts/sql/mes_assistant_knowledge_permission.sql;
-- Unset, disabled, deleted or nonexistent roles cause no grant for that scope.
-- Re-login / clear authorization cache after applying an approved grant.

START TRANSACTION;

SET @assistant_knowledge_role_valid = (
    SELECT COUNT(*) FROM sp_sys_role
    WHERE id = @assistant_knowledge_role_id AND is_deleted = '0'
);
SET @assistant_restricted_role_valid = (
    SELECT COUNT(*) FROM sp_sys_role
    WHERE id = @assistant_restricted_role_id AND is_deleted = '0'
);

INSERT INTO sp_sys_menu
    (id, code, name, url, parent_id, grade, sort_num, type, permission, icon, descr,
     create_time, create_username, update_time, update_username)
SELECT 'mes-assistant-knowledge-read', 'mesAssistantKnowledgeRead', '助手公开知识查询', '#',
       '0', '1', 1000, '2', 'mes:assistant:knowledge:read', '', '助手公开文档只读检索权限',
       NOW(), 'assistant-permission-script', NOW(), 'assistant-permission-script'
FROM DUAL
WHERE @assistant_knowledge_role_valid = 1
  AND NOT EXISTS (SELECT 1 FROM sp_sys_menu WHERE permission = 'mes:assistant:knowledge:read');

INSERT INTO sp_sys_menu
    (id, code, name, url, parent_id, grade, sort_num, type, permission, icon, descr,
     create_time, create_username, update_time, update_username)
SELECT 'mes-assistant-knowledge-restricted', 'mesAssistantKnowledgeRestricted', '助手受限知识查询', '#',
       '0', '1', 1001, '2', 'mes:assistant:knowledge:restricted', '', '助手受限文档附加检索权限；还需公开知识查询权限',
       NOW(), 'assistant-permission-script', NOW(), 'assistant-permission-script'
FROM DUAL
WHERE @assistant_restricted_role_valid = 1
  AND NOT EXISTS (SELECT 1 FROM sp_sys_menu WHERE permission = 'mes:assistant:knowledge:restricted');

SET @assistant_knowledge_menu_id = (
    SELECT id FROM sp_sys_menu WHERE permission = 'mes:assistant:knowledge:read' AND type = '2'
    ORDER BY id LIMIT 1
);
SET @assistant_restricted_menu_id = (
    SELECT id FROM sp_sys_menu WHERE permission = 'mes:assistant:knowledge:restricted' AND type = '2'
    ORDER BY id LIMIT 1
);

INSERT INTO sp_sys_role_menu
    (id, role_id, menu_id, create_time, create_username, update_time, update_username)
SELECT REPLACE(UUID(), '-', ''), @assistant_knowledge_role_id, @assistant_knowledge_menu_id,
       NOW(), 'assistant-permission-script', NOW(), 'assistant-permission-script'
FROM DUAL
WHERE @assistant_knowledge_role_valid = 1 AND @assistant_knowledge_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sp_sys_role_menu WHERE role_id = @assistant_knowledge_role_id AND menu_id = @assistant_knowledge_menu_id);

INSERT INTO sp_sys_role_menu
    (id, role_id, menu_id, create_time, create_username, update_time, update_username)
SELECT REPLACE(UUID(), '-', ''), @assistant_restricted_role_id, @assistant_restricted_menu_id,
       NOW(), 'assistant-permission-script', NOW(), 'assistant-permission-script'
FROM DUAL
WHERE @assistant_restricted_role_valid = 1 AND @assistant_restricted_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sp_sys_role_menu WHERE role_id = @assistant_restricted_role_id AND menu_id = @assistant_restricted_menu_id);

COMMIT;

SELECT CASE WHEN @assistant_knowledge_role_valid = 1 AND @assistant_knowledge_menu_id IS NOT NULL
    THEN 'READY: public knowledge read registered for the explicitly selected role'
    ELSE 'NO_CHANGE: select a valid @assistant_knowledge_role_id'
END AS assistant_knowledge_permission_status,
CASE WHEN @assistant_restricted_role_valid = 1 AND @assistant_restricted_menu_id IS NOT NULL
    THEN 'READY: restricted knowledge registered for the separately selected role'
    ELSE 'NO_CHANGE: no restricted scope grant'
END AS assistant_restricted_permission_status;
