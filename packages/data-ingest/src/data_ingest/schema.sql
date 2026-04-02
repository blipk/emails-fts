-- Enron Email Dataset - SQLite Schema
-- Core email message metadata
CREATE TABLE IF NOT EXISTS message (
    mid         INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id  TEXT NOT NULL UNIQUE,  -- MIME Message-ID header
    in_reply_to TEXT,                  -- MIME In-Reply-To header
    in_reply_to_resolved INTEGER,      -- Backfilled FK to message(mid) from resolved quoted references
    date        TEXT NOT NULL,         -- ISO 8601 with timezone offset (e.g. 2001-10-29T14:30:00-06:00)
    sender      TEXT NOT NULL,         -- From header email address
    sender_name TEXT,                  -- From header display name
    subject     TEXT,
    body_plain  TEXT,                  -- Plain text body
    body_html   TEXT,                  -- HTML body (if present)
    content_type TEXT NOT NULL,        -- Top-level Content-Type (e.g. multipart/mixed)
    charset     TEXT,                  -- Character encoding of the body
    x_origin    TEXT,                  -- X-Origin header (mailbox provenance)
    x_folder    TEXT,                  -- X-Folder header (original folder path)
    source_path TEXT NOT NULL,         -- Filesystem path of the original MIME file
    has_attachments INTEGER NOT NULL DEFAULT 0,  -- Denormalised flag for quick filtering
    raw_headers TEXT NOT NULL          -- Complete original MIME headers preserved as-is for analysis
);

-- Recipients normalised per type, one row per recipient per message
CREATE TABLE IF NOT EXISTS recipient (
    rid         INTEGER PRIMARY KEY AUTOINCREMENT,
    mid         INTEGER NOT NULL REFERENCES message(mid) ON DELETE CASCADE,
    rtype       TEXT NOT NULL CHECK (rtype IN ('to', 'cc', 'bcc')),
    address     TEXT NOT NULL,
    display_name TEXT
);

-- Threading references, one row per referenced message_id per message
-- Preserves the full References header chain in order
CREATE TABLE IF NOT EXISTS thread_reference (
    trid        INTEGER PRIMARY KEY AUTOINCREMENT,
    mid         INTEGER NOT NULL REFERENCES message(mid) ON DELETE CASCADE,
    referenced_message_id TEXT NOT NULL,  -- A single Message-ID from the References header
    position    INTEGER NOT NULL          -- Order in the References chain (0 = oldest ancestor)
);

-- Attachment metadata (not the binary content)
CREATE TABLE IF NOT EXISTS attachment (
    aid         INTEGER PRIMARY KEY AUTOINCREMENT,
    mid         INTEGER NOT NULL REFERENCES message(mid) ON DELETE CASCADE,
    filename    TEXT,
    content_type TEXT NOT NULL,           -- MIME type (e.g. application/pdf)
    content_disposition TEXT,             -- inline or attachment
    size_bytes  INTEGER,                  -- Decoded content size
    charset     TEXT,                     -- Encoding if text-based attachment
    source      TEXT NOT NULL DEFAULT 'mime' -- 'mime' = from MIME part, 'body_reference' = inferred from <<filename>> in body text
);

-- Employee directory (imported from MySQL dump)
CREATE TABLE IF NOT EXISTS employee (
    eid         INTEGER PRIMARY KEY AUTOINCREMENT,
    first_name  TEXT,
    last_name   TEXT,
    email_primary TEXT NOT NULL UNIQUE,
    folder      TEXT,                     -- Original mailbox folder name
    status      TEXT                      -- Last known job title/position
);

-- Bridge table mapping employees to all their known email addresses
CREATE TABLE IF NOT EXISTS employee_email (
    eid         INTEGER NOT NULL REFERENCES employee(eid) ON DELETE CASCADE,
    address     TEXT NOT NULL UNIQUE,     -- Email address (primary or alternate)
    is_primary  INTEGER NOT NULL DEFAULT 0, -- 1 if this is the canonical address, 0 for alternates
    PRIMARY KEY (eid, address)
);

-- Normalized email headers, one row per header per message.
-- Stores all MIME headers for structured querying and display.
CREATE TABLE IF NOT EXISTS email_header (
    hid         INTEGER PRIMARY KEY AUTOINCREMENT,
    mid         INTEGER NOT NULL REFERENCES message(mid) ON DELETE CASCADE,
    name        TEXT NOT NULL,           -- Header name as-is from MIME (e.g. "X-From", "Content-Type")
    value       TEXT NOT NULL,           -- Decoded header value
    position    INTEGER NOT NULL         -- Order of appearance in the original headers (0-based)
);

-- Quoted reply/forward references, parsed from body_plain during import.
-- Reconstructs thread links by extracting inline-quoted headers from email bodies.
-- Each row represents one quoted block (emails can contain multiple nested quotes).
CREATE TABLE IF NOT EXISTS message_reference (
    mrid            INTEGER PRIMARY KEY AUTOINCREMENT,
    mid             INTEGER NOT NULL REFERENCES message(mid) ON DELETE CASCADE,
    quoted_sender   TEXT,           -- Parsed From/sender in the quoted block
    quoted_date     TEXT,           -- Parsed Sent/Date in the quoted block (ISO 8601 when parseable)
    quoted_subject  TEXT,           -- Parsed Subject in the quoted block
    resolved_mid    INTEGER,        -- FK to message(mid) if the quoted email was matched
    position        INTEGER NOT NULL -- Order of quote in the email (0 = most recent/innermost)
);

-- Indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_message_sender      ON message(sender);
CREATE INDEX IF NOT EXISTS idx_message_date        ON message(date);
CREATE INDEX IF NOT EXISTS idx_message_message_id  ON message(message_id);
CREATE INDEX IF NOT EXISTS idx_message_in_reply_to ON message(in_reply_to);
CREATE INDEX IF NOT EXISTS idx_message_reply_resolved ON message(in_reply_to_resolved);
CREATE INDEX IF NOT EXISTS idx_recipient_mid       ON recipient(mid);
CREATE INDEX IF NOT EXISTS idx_recipient_address   ON recipient(address);
CREATE INDEX IF NOT EXISTS idx_thread_ref_mid      ON thread_reference(mid);
CREATE INDEX IF NOT EXISTS idx_thread_ref_ref_id   ON thread_reference(referenced_message_id);
CREATE INDEX IF NOT EXISTS idx_attachment_mid      ON attachment(mid);
CREATE INDEX IF NOT EXISTS idx_header_mid          ON email_header(mid);
CREATE INDEX IF NOT EXISTS idx_header_name         ON email_header(name);
CREATE INDEX IF NOT EXISTS idx_header_name_value   ON email_header(name, value);
CREATE INDEX IF NOT EXISTS idx_employee_email      ON employee(email_primary);
CREATE INDEX IF NOT EXISTS idx_employee_email_addr ON employee_email(address);
CREATE INDEX IF NOT EXISTS idx_employee_email_eid  ON employee_email(eid);
CREATE INDEX IF NOT EXISTS idx_msg_ref_mid         ON message_reference(mid);
CREATE INDEX IF NOT EXISTS idx_msg_ref_resolved    ON message_reference(resolved_mid);
CREATE INDEX IF NOT EXISTS idx_msg_ref_sender      ON message_reference(quoted_sender);
