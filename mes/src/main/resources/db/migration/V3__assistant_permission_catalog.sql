-- Sources: mes_assistant_permission.sql, mes_assistant_knowledge_permission.sql,
-- r005_multi_agent_permission.sql. Register capabilities, never grant them to existing roles.
-- Existing permission IDs and role associations are preserved.
INSERT INTO sp_sys_menu
 (id,code,name,url,parent_id,grade,sort_num,type,permission,icon,descr,
  create_time,create_username,update_time,update_username)
SELECT p.id,p.code,p.name,'#','0','1',p.sort_num,'2',p.permission,'',
 'Flyway capability catalog; role assignment requires explicit authorization',
 NOW(),'flyway-v3',NOW(),'flyway-v3'
FROM (
 SELECT 'mes-assistant-routing-read' id,'mesAssistantRoutingRead' code,'助手工艺路线查询' name,
        'mes:assistant:routing:read' permission,999 sort_num
 UNION ALL SELECT 'mes-assistant-knowledge-read','mesAssistantKnowledgeRead','助手公开知识查询','mes:assistant:knowledge:read',1000
 UNION ALL SELECT 'mes-assistant-knowledge-restricted','mesAssistantKnowledgeRestricted','助手受限知识查询','mes:assistant:knowledge:restricted',1001
 UNION ALL SELECT 'mes-assistant-material-read','mesMaterialRead','物料查询','mes:assistant:material:read',1011
 UNION ALL SELECT 'mes-assistant-operation-read','mesOperationRead','工序查询','mes:assistant:operation:read',1012
 UNION ALL SELECT 'mes-assistant-workorder-read','mesWorkOrderRead','工单查询','mes:assistant:workorder:read',1014
 UNION ALL SELECT 'mes-assistant-workorder-write','mesWorkOrderWrite','工单创建和状态操作','mes:assistant:workorder:write',1015
) p
WHERE NOT EXISTS (SELECT 1 FROM sp_sys_menu m WHERE m.permission=p.permission);
