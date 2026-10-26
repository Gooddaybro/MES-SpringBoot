-- Assistant routing read permission. No business tables are created or changed.
-- Select an existing, enabled role explicitly in the SAME MySQL session first:
--   SET @assistant_role_id = 'your-approved-existing-role-id';
--   SOURCE scripts/sql/mes_assistant_permission.sql;
-- An unset, nonexistent, deleted or disabled role causes no changes.
-- SysMenuEnum and the current schema define type '2' as a button permission.
-- Re-login / clear the subject's authorization cache after an approved grant.

START TRANSACTION;

SET @assistant_role_valid = (
    SELECT COUNT(*) FROM sp_sys_role
    WHERE id = @assistant_role_id AND is_deleted = '0'
);

INSERT INTO sp_sys_menu
    (id, code, name, url, parent_id, grade, sort_num, type, permission, icon, descr,
     create_time, create_username, update_time, update_username)
SELECT 'mes-assistant-routing-read', 'mesAssistantRoutingRead', '助手工艺路线查询', '#',
       '0', '1', 999, '2', 'mes:assistant:routing:read', '', '助手物料工艺路线只读查询权限',
       NOW(), 'assistant-permission-script', NOW(), 'assistant-permission-script'
FROM DUAL
WHERE @assistant_role_valid = 1
  AND NOT EXISTS (
      SELECT 1 FROM sp_sys_menu WHERE permission = 'mes:assistant:routing:read'
  );

SET @assistant_menu_id = (
    SELECT id FROM sp_sys_menu
    WHERE permission = 'mes:assistant:routing:read' AND type = '2'
    ORDER BY id LIMIT 1
);

INSERT INTO sp_sys_role_menu
    (id, role_id, menu_id, create_time, create_username, update_time, update_username)
SELECT REPLACE(UUID(), '-', ''), @assistant_role_id, @assistant_menu_id,
       NOW(), 'assistant-permission-script', NOW(), 'assistant-permission-script'
FROM DUAL
WHERE @assistant_role_valid = 1 AND @assistant_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sp_sys_role_menu
      WHERE role_id = @assistant_role_id AND menu_id = @assistant_menu_id
  );

COMMIT;

SELECT CASE
    WHEN @assistant_role_valid <> 1 THEN 'NO_CHANGE: explicitly select an existing enabled @assistant_role_id'
    WHEN @assistant_menu_id IS NULL THEN 'NO_CHANGE: inspect the existing assistant permission menu type'
    ELSE 'READY: assistant routing read permission is registered for the selected role'
END AS assistant_permission_status;
