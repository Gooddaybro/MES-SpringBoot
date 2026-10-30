-- Execute explicitly against 127.0.0.1:3306 using an authorized local account.
-- Creates the database only. Flyway creates the tables on the first dev startup.
CREATE DATABASE IF NOT EXISTS mes_study CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
