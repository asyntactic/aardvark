CREATE TABLE users (
  id VARCHAR(20) PRIMARY KEY,
  first_name VARCHAR(30),
  last_name VARCHAR(30),
  email VARCHAR(30),
  superadmin BOOLEAN default false,
  last_login TIMESTAMP,
  disabled BOOLEAN default false,
  pass VARCHAR(100)
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE domains (
  id CHAR(36) NOT NULL,
  name VARCHAR(30),
  description TEXT,
  ordering INT NOT NULL default 0,
  disabled BOOLEAN default false,
  KEY ordering (ordering),
  PRIMARY KEY (id)
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE users_domains (
  users_id VARCHAR(20),
  domains_id CHAR(36),
  PRIMARY KEY (users_id, domains_id),
  FOREIGN KEY (users_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE CASCADE,
  FOREIGN KEY (domains_id) REFERENCES domains(id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE entities (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36),
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE semantic_types (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36),
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE roles (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36),
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  role_type CHAR(36), -- Entity
  semantic_type CHAR(36),
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE modifiers (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36),
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  value_type CHAR(36), -- Semantic Type
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE contexts (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36),
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE modifier_values (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36), -- The context
  modifier CHAR(36) NOT NULL,
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  value VARCHAR(100),
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE schemae (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36),
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE tables (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36), -- Schema
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE columns (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36), -- Table
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  role CHAR(36),
  fk CHAR(36), -- Column
  join_type ENUM('Inner', 'Left_Outer', 'Right_Outer'),
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE datastores (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36),
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  schema_id CHAR(36),
  context_id CHAR(36),
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE semantic_functions (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36),
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  source_mv VARCHAR(100),
  target_mv VARCHAR(100),
  modifier CHAR(36),
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE relational_functions (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36),
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE formulas (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36), -- SF or RF
  function_type ENUM('Semantic', 'Relational'),
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  output VARCHAR(50),
  operation VARCHAR(50),
  arguments VARCHAR(5000),
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE conversions (
  id CHAR(36) NOT NULL,
  valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  valid_to TIMESTAMP NULL,
  domain_id CHAR(36) NOT NULL,
  parent_id CHAR(36),
  name VARCHAR(50),
  description TEXT,
  ordering INT NOT NULL default 0,
  source_datastore CHAR(36),
  target_datastore CHAR(36),
  roles TEXT, -- Comma-separated UUIDs
  KEY ordering (ordering),
  PRIMARY KEY (id, valid_from),
  FOREIGN KEY (domain_id) REFERENCES domains(id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=INNODB CHARACTER SET utf8 COLLATE utf8_general_ci;

