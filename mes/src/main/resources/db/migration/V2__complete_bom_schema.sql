-- The 20200330 snapshot omitted the detail table used by SpBomItem.java.
-- IF NOT EXISTS supports an audited legacy database with a manually added table.
CREATE TABLE IF NOT EXISTS sp_bom_item (
    id VARCHAR(64) NOT NULL,
    bom_head_id VARCHAR(64) NULL,
    materiel_item_code VARCHAR(255) NULL,
    materiel_item_desc VARCHAR(255) NULL,
    line_no VARCHAR(64) NULL,
    item_num DECIMAL(18,3) NULL,
    item_unit VARCHAR(255) NULL,
    oper_typer VARCHAR(255) NULL,
    create_time DATETIME NOT NULL,
    create_username VARCHAR(64) NOT NULL,
    update_time DATETIME NOT NULL,
    update_username VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_sp_bom_item_head (bom_head_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- SpBom.java maps materielCode to materiel_code, while the snapshot has materiel_id.
-- Preserve materiel_id and all existing values; their business meaning needs manual review.
SET @mes_bom_add_code = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='sp_bom' AND column_name='materiel_code'),
    'SELECT 1',
    'ALTER TABLE sp_bom ADD COLUMN materiel_code VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT ''Material code; legacy materiel_id preserved''');
PREPARE mes_bom_statement FROM @mes_bom_add_code;
EXECUTE mes_bom_statement;
DEALLOCATE PREPARE mes_bom_statement;
