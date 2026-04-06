# data-ingest

Parses the Enron MIME email dataset (~500k emails) and MySQL employee dump into a normalised SQLite database. Uses Python's built-in `email` module for MIME parsing and `sqlglot` for MySQL-to-SQLite transpilation of the employee data.


## Data Sources

These email data dump files are required:

- `enron_mail_20150507.tar.gz` — MIME email archive
- `enron-mysqldump_v5.sql.gz` — MySQL employee directory dump

They are searched for in the `./src/data_ingest/data` directory by default, archives are extracted to `data/extracted/` on first run and reused on subsequent runs.

The data files are available at these links:

- http://www.ahschulz.de/enron-email-data/
- https://www.cs.cmu.edu/~enron/enron_mail_20150507.tar.gz

## Usage

```bash
# Full import (extracts bundled tar.gz + MySQL dump, outputs enron_emails.db)
uv run data-ingest

# Import only employees (fast, for testing)
uv run data-ingest --skip-emails

# Import only emails (skip employee directory)
uv run data-ingest --skip-employees

# Custom paths
uv run data-ingest --db-path ./output.db --mime-dir /path/to/maildir --mysql-dump /path/to/dump.sql.gz

# Validate an existing database
uv run data-ingest --validate-only --db-path ./enron_emails.db

# Verbose logging
uv run data-ingest -v
```

### Clean / Continue modes

If a database already exists when you run the importer, you'll be prompted to either continue a previous import or start clean. You can also set this explicitly:

```bash
# Delete existing database and start fresh
uv run data-ingest --clean

# Resume a previously interrupted import (skips already-imported emails)
uv run data-ingest --continue
```

## Schema

Six tables: `message`, `recipient`, `thread_reference`, `attachment`, `employee`, `employee_email`. See [schema.sql](src/data_ingest/schema.sql) for full definitions.
