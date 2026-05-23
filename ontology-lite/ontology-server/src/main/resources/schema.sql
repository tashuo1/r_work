CREATE TABLE IF NOT EXISTS om_platform_state (
  state_key VARCHAR(64) PRIMARY KEY,
  payload LONGTEXT NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS om_compute_resource_health;
DROP TABLE IF EXISTS om_compute_resource;

CREATE TABLE IF NOT EXISTS om_compute_resource (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL DEFAULT 1,
  compute_resource_id BIGINT NOT NULL,
  resource_code VARCHAR(64) NOT NULL,
  resource_name VARCHAR(128) NOT NULL,
  store_type VARCHAR(16) NOT NULL,
  db_type VARCHAR(32) NOT NULL,
  host VARCHAR(128) NOT NULL,
  port INT NOT NULL,
  database_name VARCHAR(128) NOT NULL,
  schema_name VARCHAR(128) NOT NULL,
  username VARCHAR(128) NOT NULL,
  secret_ref VARCHAR(256) NOT NULL,
  password_value VARCHAR(512) NOT NULL,
  jdbc_url VARCHAR(1024) NOT NULL,
  status VARCHAR(16) NOT NULL,
  health_status VARCHAR(32) NOT NULL,
  health_message VARCHAR(512),
  initialized TINYINT NOT NULL DEFAULT 0,
  active TINYINT NOT NULL DEFAULT 0,
  last_health_time TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS om_compute_resource_health (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL DEFAULT 1,
  compute_resource_id BIGINT NOT NULL,
  store_type VARCHAR(16) NOT NULL,
  database_name VARCHAR(128) NOT NULL,
  check_time TIMESTAMP NOT NULL,
  health_status VARCHAR(32) NOT NULL,
  error_message VARCHAR(512),
  response_ms INT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
