-- TEACHING SAMPLES ONLY. No production specification or BOM is implied.
-- MySQL 8, InnoDB; run with a client that aborts on errors (do not use --force).
-- Explicit opt-in, SAME SESSION, isolated learning DB only:
--   USE mes_study;
--   SET @mes_servo_sample_confirm = 'LOCAL_LEARNING_ONLY';
--   SOURCE scripts/sql/servo_controller_sample.sql;
-- Default is NO_CHANGE. Existing nonmatching IDs/codes/relations refuse the ENTIRE seed.
-- Temporary tables are connection-local; no persistent schema or routine is added.

SET NAMES utf8mb4;
DROP TEMPORARY TABLE IF EXISTS tmp_mes_servo_material;
DROP TEMPORARY TABLE IF EXISTS tmp_mes_servo_flow;
DROP TEMPORARY TABLE IF EXISTS tmp_mes_servo_oper;
DROP TEMPORARY TABLE IF EXISTS tmp_mes_servo_relation;
CREATE TEMPORARY TABLE tmp_mes_servo_material LIKE sp_materile;
CREATE TEMPORARY TABLE tmp_mes_servo_flow LIKE sp_flow;
CREATE TEMPORARY TABLE tmp_mes_servo_oper LIKE sp_oper;
CREATE TEMPORARY TABLE tmp_mes_servo_relation LIKE sp_flow_oper_relation;

INSERT INTO tmp_mes_servo_flow
 (id,flow,flow_desc,process,create_time,create_username,update_time,update_username)
VALUES ('sample-servo-flow','SERVO-DEMO-ROUTE-001','教学样例：伺服电机控制器装配与检测',
 '备料核对→PCBA与接口装配→壳体与线束装配→固件烧录→绝缘与低压功能测试→闭环联调与终检',
 NOW(),'servo-sample-seed',NOW(),'servo-sample-seed');

INSERT INTO tmp_mes_servo_material
 (id,materiel,materiel_desc,unit,product_group,mat_type,model,flow_id,flow_desc,
  create_time,create_username,update_time,update_username,is_deleted)
VALUES
 ('sample-servo-mat-controller','SERVO-DEMO-CTRL-001','教学样例：伺服电机控制器成品','PCS','SERVO-DEMO','FG','DEMO-CTRL',
 'sample-servo-flow','教学样例：伺服电机控制器装配与检测',NOW(),'servo-sample-seed',NOW(),'servo-sample-seed','0'),
 ('sample-servo-mat-pcba','SERVO-DEMO-PCBA-001','教学样例：控制板PCBA','PCS','SERVO-DEMO','PG','DEMO-PCBA',
 NULL,NULL,NOW(),'servo-sample-seed',NOW(),'servo-sample-seed','0'),
 ('sample-servo-mat-encoder','SERVO-DEMO-ENC-001','教学样例：编码器接口件','PCS','SERVO-DEMO','material','DEMO-ENC',
 NULL,NULL,NOW(),'servo-sample-seed',NOW(),'servo-sample-seed','0'),
 ('sample-servo-mat-case','SERVO-DEMO-CASE-001','教学样例：散热壳体','PCS','SERVO-DEMO','material','DEMO-CASE',
 NULL,NULL,NOW(),'servo-sample-seed',NOW(),'servo-sample-seed','0'),
 ('sample-servo-mat-power','SERVO-DEMO-PWR-001','教学样例：电源连接器','PCS','SERVO-DEMO','material','DEMO-PWR',
 NULL,NULL,NOW(),'servo-sample-seed',NOW(),'servo-sample-seed','0'),
 ('sample-servo-mat-wire','SERVO-DEMO-WIRE-001','教学样例：内部线束','PCS','SERVO-DEMO','material','DEMO-WIRE',
 NULL,NULL,NOW(),'servo-sample-seed',NOW(),'servo-sample-seed','0');

INSERT INTO tmp_mes_servo_oper
 (id,oper,oper_desc,create_time,create_username,update_time,update_username)
VALUES
 ('sample-servo-op-10','SERVO-DEMO-OP10','教学样例：备料与来料外观核对',NOW(),'servo-sample-seed',NOW(),'servo-sample-seed'),
 ('sample-servo-op-20','SERVO-DEMO-OP20','教学样例：PCBA与编码器接口装配',NOW(),'servo-sample-seed',NOW(),'servo-sample-seed'),
 ('sample-servo-op-30','SERVO-DEMO-OP30','教学样例：散热壳体与线束装配',NOW(),'servo-sample-seed',NOW(),'servo-sample-seed'),
 ('sample-servo-op-40','SERVO-DEMO-OP40','教学样例：固件烧录与参数核对',NOW(),'servo-sample-seed',NOW(),'servo-sample-seed'),
 ('sample-servo-op-50','SERVO-DEMO-OP50','教学样例：绝缘与低压功能测试',NOW(),'servo-sample-seed',NOW(),'servo-sample-seed'),
 ('sample-servo-op-60','SERVO-DEMO-OP60','教学样例：编码器闭环联调与终检',NOW(),'servo-sample-seed',NOW(),'servo-sample-seed');

INSERT INTO tmp_mes_servo_relation
 (id,flow_id,flow,per_oper_id,per_oper,oper_id,oper,next_oper_id,next_oper,sort_num,oper_type,
  create_time,create_username,update_time,update_username)
VALUES
 ('sample-servo-rel-10','sample-servo-flow','SERVO-DEMO-ROUTE-001',NULL,NULL,'sample-servo-op-10','SERVO-DEMO-OP10','sample-servo-op-20','SERVO-DEMO-OP20',1,'firstOper',NOW(),'servo-sample-seed',NOW(),'servo-sample-seed'),
 ('sample-servo-rel-20','sample-servo-flow','SERVO-DEMO-ROUTE-001','sample-servo-op-10','SERVO-DEMO-OP10','sample-servo-op-20','SERVO-DEMO-OP20','sample-servo-op-30','SERVO-DEMO-OP30',2,NULL,NOW(),'servo-sample-seed',NOW(),'servo-sample-seed'),
 ('sample-servo-rel-30','sample-servo-flow','SERVO-DEMO-ROUTE-001','sample-servo-op-20','SERVO-DEMO-OP20','sample-servo-op-30','SERVO-DEMO-OP30','sample-servo-op-40','SERVO-DEMO-OP40',3,NULL,NOW(),'servo-sample-seed',NOW(),'servo-sample-seed'),
 ('sample-servo-rel-40','sample-servo-flow','SERVO-DEMO-ROUTE-001','sample-servo-op-30','SERVO-DEMO-OP30','sample-servo-op-40','SERVO-DEMO-OP40','sample-servo-op-50','SERVO-DEMO-OP50',4,NULL,NOW(),'servo-sample-seed',NOW(),'servo-sample-seed'),
 ('sample-servo-rel-50','sample-servo-flow','SERVO-DEMO-ROUTE-001','sample-servo-op-40','SERVO-DEMO-OP40','sample-servo-op-50','SERVO-DEMO-OP50','sample-servo-op-60','SERVO-DEMO-OP60',5,NULL,NOW(),'servo-sample-seed',NOW(),'servo-sample-seed'),
 ('sample-servo-rel-60','sample-servo-flow','SERVO-DEMO-ROUTE-001','sample-servo-op-50','SERVO-DEMO-OP50','sample-servo-op-60','SERVO-DEMO-OP60',NULL,NULL,6,'lastOper',NOW(),'servo-sample-seed',NOW(),'servo-sample-seed');

START TRANSACTION;
-- A reviewed single-operator seed. Stop business writers while applying; no business-code UNIQUE keys exist.
SET @mes_servo_material_conflicts =
 (SELECT COUNT(*) FROM sp_materile t JOIN tmp_mes_servo_material s ON t.id=s.id OR t.materiel=s.materiel
  WHERE NOT (BINARY t.id <=> BINARY s.id AND BINARY t.materiel <=> BINARY s.materiel
   AND BINARY t.materiel_desc <=> BINARY s.materiel_desc AND BINARY t.unit <=> BINARY s.unit
   AND BINARY t.product_group <=> BINARY s.product_group AND BINARY t.mat_type <=> BINARY s.mat_type
   AND BINARY t.model <=> BINARY s.model AND t.size IS NULL AND BINARY t.flow_id <=> BINARY s.flow_id
   AND BINARY t.flow_desc <=> BINARY s.flow_desc AND t.is_deleted <=> s.is_deleted
   AND t.create_username='servo-sample-seed' AND t.update_username='servo-sample-seed'));
SET @mes_servo_flow_conflicts =
 (SELECT COUNT(*) FROM sp_flow t JOIN tmp_mes_servo_flow s ON t.id=s.id OR t.flow=s.flow
  WHERE NOT (BINARY t.id <=> BINARY s.id AND BINARY t.flow <=> BINARY s.flow AND BINARY t.flow_desc <=> BINARY s.flow_desc
   AND BINARY t.process <=> BINARY s.process AND t.create_username='servo-sample-seed' AND t.update_username='servo-sample-seed'));
SET @mes_servo_oper_conflicts =
 (SELECT COUNT(*) FROM sp_oper t JOIN tmp_mes_servo_oper s ON t.id=s.id OR t.oper=s.oper
  WHERE NOT (BINARY t.id <=> BINARY s.id AND BINARY t.oper <=> BINARY s.oper AND BINARY t.oper_desc <=> BINARY s.oper_desc
   AND t.create_username='servo-sample-seed' AND t.update_username='servo-sample-seed'));
SET @mes_servo_relation_conflicts =
 (SELECT COUNT(*) FROM sp_flow_oper_relation t JOIN tmp_mes_servo_relation s
    ON t.id=s.id OR (t.flow_id=s.flow_id AND (t.sort_num=s.sort_num OR t.oper_id=s.oper_id))
  WHERE NOT (BINARY t.id <=> BINARY s.id AND BINARY t.flow_id <=> BINARY s.flow_id AND BINARY t.flow <=> BINARY s.flow
   AND BINARY t.per_oper_id <=> BINARY s.per_oper_id AND BINARY t.per_oper <=> BINARY s.per_oper
   AND BINARY t.oper_id <=> BINARY s.oper_id AND BINARY t.oper <=> BINARY s.oper
   AND BINARY t.next_oper_id <=> BINARY s.next_oper_id AND BINARY t.next_oper <=> BINARY s.next_oper
   AND t.sort_num <=> s.sort_num AND BINARY t.oper_type <=> BINARY s.oper_type
   AND t.create_username='servo-sample-seed' AND t.update_username='servo-sample-seed'));
SET @mes_servo_extra_relations =
 (SELECT COUNT(*) FROM sp_flow_oper_relation t WHERE t.flow_id='sample-servo-flow'
    AND NOT EXISTS (SELECT 1 FROM tmp_mes_servo_relation s WHERE s.id=t.id));
SET @mes_servo_conflicts = @mes_servo_material_conflicts + @mes_servo_flow_conflicts
 + @mes_servo_oper_conflicts + @mes_servo_relation_conflicts + @mes_servo_extra_relations;

SET @mes_servo_allowed = IF(DATABASE()='mes_study' AND @@port=3308
 AND @mes_servo_sample_confirm='LOCAL_LEARNING_ONLY' AND @mes_servo_conflicts=0,1,0);

INSERT INTO sp_flow SELECT s.* FROM tmp_mes_servo_flow s
 WHERE @mes_servo_allowed=1 AND NOT EXISTS (SELECT 1 FROM sp_flow t WHERE t.id=s.id);
INSERT INTO sp_oper SELECT s.* FROM tmp_mes_servo_oper s
 WHERE @mes_servo_allowed=1 AND NOT EXISTS (SELECT 1 FROM sp_oper t WHERE t.id=s.id);
INSERT INTO sp_materile SELECT s.* FROM tmp_mes_servo_material s
 WHERE @mes_servo_allowed=1 AND NOT EXISTS (SELECT 1 FROM sp_materile t WHERE t.id=s.id);
INSERT INTO sp_flow_oper_relation SELECT s.* FROM tmp_mes_servo_relation s
 WHERE @mes_servo_allowed=1 AND NOT EXISTS (SELECT 1 FROM sp_flow_oper_relation t WHERE t.id=s.id);
COMMIT;

SELECT IF(@mes_servo_allowed=1,'READY: servo teaching samples present; existing rows preserved',
 'NO_CHANGE: check explicit local opt-in, database/port and collisions') AS servo_sample_status,
 @mes_servo_conflicts AS conflicts;
DROP TEMPORARY TABLE tmp_mes_servo_material;
DROP TEMPORARY TABLE tmp_mes_servo_flow;
DROP TEMPORARY TABLE tmp_mes_servo_oper;
DROP TEMPORARY TABLE tmp_mes_servo_relation;
