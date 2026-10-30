-- Local learning permissions only. Choose the role explicitly in the SAME session:
-- SET @r005_role_id='approved-learning-role-id'; SOURCE this file.
-- No role selected means no grants. Restricted document permission is never included.
START TRANSACTION;
SET @r005_role_valid = (SELECT COUNT(*) FROM sp_sys_role
    WHERE id=@r005_role_id AND is_deleted='0' AND DATABASE()='mes_study' AND @@port=3308);

INSERT INTO sp_sys_menu
    (id,code,name,url,parent_id,grade,sort_num,type,permission,icon,descr,
     create_time,create_username,update_time,update_username)
SELECT p.id,p.code,p.name,'#','0','1',1010+p.n,'2',p.permission,'','R005 local business permission',
       NOW(),'r005-permission',NOW(),'r005-permission'
FROM (
    SELECT 'mes-assistant-material-read' id,'mesMaterialRead' code,'物料查询' name,'mes:assistant:material:read' permission,1 n
    UNION ALL SELECT 'mes-assistant-operation-read','mesOperationRead','工序查询','mes:assistant:operation:read',2
    UNION ALL SELECT 'mes-assistant-routing-read','mesRoutingRead','工艺路线查询','mes:assistant:routing:read',3
    UNION ALL SELECT 'mes-assistant-workorder-read','mesWorkOrderRead','工单查询','mes:assistant:workorder:read',4
    UNION ALL SELECT 'mes-assistant-workorder-write','mesWorkOrderWrite','工单创建和状态操作','mes:assistant:workorder:write',5
) p
WHERE @r005_role_valid=1 AND NOT EXISTS (SELECT 1 FROM sp_sys_menu m WHERE m.permission=p.permission);

INSERT INTO sp_sys_role_menu (id,role_id,menu_id,create_time,create_username,update_time,update_username)
SELECT REPLACE(UUID(),'-',''),@r005_role_id,m.id,NOW(),'r005-permission',NOW(),'r005-permission'
FROM sp_sys_menu m
WHERE @r005_role_valid=1 AND m.type='2'
  AND m.permission IN ('mes:assistant:material:read','mes:assistant:operation:read','mes:assistant:routing:read',
                       'mes:assistant:workorder:read','mes:assistant:workorder:write')
  AND NOT EXISTS (SELECT 1 FROM sp_sys_role_menu r WHERE r.role_id=@r005_role_id AND r.menu_id=m.id);
COMMIT;
SELECT IF(@r005_role_valid=1,'READY: selected local role has four business reads and explicit work-order writes',
          'NO_CHANGE: select an existing role on mes_study port 3308') AS r005_permissions;
