-- Additive R005 work-order schema; MySQL 8. No legacy table or row is changed.
-- Back up the target database and apply this file explicitly before using work orders.
CREATE TABLE IF NOT EXISTS mes_work_order (
    id CHAR(36) NOT NULL,
    order_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    material_id VARCHAR(64) NOT NULL,
    material_code VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    material_name VARCHAR(255) NOT NULL,
    unit VARCHAR(255) NOT NULL,
    planned_quantity DECIMAL(18,3) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    route_id VARCHAR(64) NOT NULL,
    route_code VARCHAR(255) NOT NULL,
    route_snapshot_json LONGTEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_by VARCHAR(128) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mes_work_order_code (order_code),
    KEY ix_mes_work_order_material (material_code, created_at),
    CONSTRAINT ck_mes_work_order_quantity CHECK (planned_quantity > 0),
    CONSTRAINT ck_mes_work_order_state CHECK (state IN ('CREATED','IN_PROGRESS','COMPLETED','CANCELLED')),
    CONSTRAINT ck_mes_work_order_snapshot CHECK (JSON_VALID(route_snapshot_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
