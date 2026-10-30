-- TEACHING DATA ONLY. Requires existing authorized SERVO-DEMO master data.
-- Explicit opt-in in the SAME session on the local learning DB:
-- SET @r005_sample_confirm='LOCAL_LEARNING_ONLY'; SOURCE this file.
-- Repeat execution leaves an existing sample's state and route snapshot intact.
SET @r005_old_concat_limit=@@session.group_concat_max_len;
SET SESSION group_concat_max_len=262144;
START TRANSACTION;
SET @r005_snapshot = (
    SELECT JSON_OBJECT(
        'material',JSON_OBJECT('id',m.id,'code',m.materiel,'name',m.materiel_desc,'status',m.is_deleted),
        'routing',JSON_OBJECT('id',f.id,'code',f.flow,'name',f.flow_desc),
        'steps',CAST(CONCAT('[',GROUP_CONCAT(JSON_OBJECT('relationId',r.id,'operationId',o.id,
            'order',r.sort_num,'code',o.oper,'name',o.oper_desc) ORDER BY r.sort_num SEPARATOR ','),']') AS JSON))
    FROM sp_materile m JOIN sp_flow f ON f.id=m.flow_id
    JOIN sp_flow_oper_relation r ON r.flow_id=f.id JOIN sp_oper o ON o.id=r.oper_id
    WHERE m.id='sample-servo-mat-controller' AND m.materiel='SERVO-DEMO-CTRL-001' AND m.is_deleted='0'
    GROUP BY m.id,m.materiel,m.materiel_desc,m.is_deleted,f.id,f.flow,f.flow_desc
    HAVING COUNT(*)=COUNT(DISTINCT r.sort_num) AND MIN(r.sort_num)>0 AND COUNT(*)=6
);
SET @r005_sample_allowed=IF(DATABASE()='mes_study' AND @@port=3308
    AND @r005_sample_confirm='LOCAL_LEARNING_ONLY' AND @r005_snapshot IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM mes_work_order WHERE
        (id='00500000-0000-4000-8000-000000000001' OR order_code='WO-SERVO-DEMO-001')
        AND NOT(id='00500000-0000-4000-8000-000000000001' AND order_code='WO-SERVO-DEMO-001'
            AND material_id='sample-servo-mat-controller' AND planned_quantity=10 AND created_by='r005-teaching-seed')),1,0);
INSERT INTO mes_work_order
    (id,order_code,material_id,material_code,material_name,unit,planned_quantity,state,route_id,route_code,
     route_snapshot_json,version,created_at,updated_at,created_by,updated_by)
SELECT '00500000-0000-4000-8000-000000000001','WO-SERVO-DEMO-001',m.id,m.materiel,m.materiel_desc,m.unit,10,
       'CREATED',f.id,f.flow,@r005_snapshot,0,NOW(3),NOW(3),'r005-teaching-seed','r005-teaching-seed'
FROM sp_materile m JOIN sp_flow f ON f.id=m.flow_id
WHERE @r005_sample_allowed=1 AND m.id='sample-servo-mat-controller'
  AND NOT EXISTS(SELECT 1 FROM mes_work_order WHERE order_code='WO-SERVO-DEMO-001');
COMMIT;
SET SESSION group_concat_max_len=@r005_old_concat_limit;
SELECT IF(@r005_sample_allowed=1,'READY: teaching work order WO-SERVO-DEMO-001 preserved or created',
          'NO_CHANGE: local opt-in, complete sample route or conflict check failed') AS r005_sample;
