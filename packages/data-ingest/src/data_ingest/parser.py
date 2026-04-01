"""Parses MIME email files and MySQL employee dump into the SQLite database."""

import gzip
import logging
import re
import sqlite3
from datetime import datetime, timezone
from email import policy
from email.parser import BytesParser
from email.utils import parseaddr, parsedate_to_datetime, getaddresses
from importlib import resources
from pathlib import Path
from typing import TYPE_CHECKING

import sqlglot

if TYPE_CHECKING:
    from email.message import EmailMessage as StdEmailMessage

from data_ingest.models import (
    Attachment,
    EmailMessage,
    Employee,
    EmployeeEmail,
    ImportResult,
    QuotedReference,
    Recipient,
    ThreadReference,
    ValidationResult,
)

logger = logging.getLogger(__name__)

_MESSAGE_ID_RE = re.compile(r"<[^>]+>")

# Patterns for detecting quoted reply/forward blocks in email bodies
_ORIGINAL_MSG_SEP = re.compile(r"-{3,}\s*Original Message\s*-{3,}", re.IGNORECASE)
_FORWARDED_SEP = re.compile(r"-{3,}\s*Forwarded by\b.*?-{3,}", re.IGNORECASE | re.DOTALL)

# Header patterns within quoted blocks (Outlook/Exchange style)
_QUOTED_FROM_RE = re.compile(r"^(?:From:?)\s*[:\t]?\s*(.+)", re.IGNORECASE | re.MULTILINE)
_QUOTED_SENT_RE = re.compile(r"^(?:Sent|Date):?\s*[:\t]?\s*(.+)", re.IGNORECASE | re.MULTILINE)
_QUOTED_SUBJECT_RE = re.compile(r"^Subject:?\s*[:\t]?\s*(.+)", re.IGNORECASE | re.MULTILINE)

# Lotus Notes forwarded-by format: "From: Name/Org@Domain on MM/DD/YYYY HH:MM AM"
_NOTES_FROM_RE = re.compile(
    r"^From:\s+(.+?)\s+on\s+(\d{1,2}/\d{1,2}/\d{4}\s+\d{1,2}:\d{2}\s*(?:AM|PM)?)",
    re.IGNORECASE | re.MULTILINE,
)


def _extract_message_ids(header_value: str) -> list[str]:
    """Extract all <message-id> values from a References or In-Reply-To header."""
    if not header_value:
        return []
    return _MESSAGE_ID_RE.findall(header_value)


_SUBJECT_PREFIX_RE = re.compile(r"^(Re|Fwd?|FW)\s*:\s*", re.IGNORECASE)


def _strip_subject_prefixes(subject: str) -> str:
    """Strip Re:/Fwd:/FW: prefixes from an email subject for comparison.

    Args:
        subject: Raw subject string.

    Returns:
        Subject with all leading reply/forward prefixes removed, lowercased and stripped.
    """
    result = subject
    while True:
        new_result = _SUBJECT_PREFIX_RE.sub("", result)
        if new_result == result:
            break
        result = new_result
    return result.strip().lower()


def _safe_decode(value: str | None) -> str | None:
    """Return stripped string or None if empty."""
    if value is None:
        return None
    stripped = value.strip()
    return stripped if stripped else None


def _parse_quoted_references(body: str) -> list[QuotedReference]:
    """Extract quoted reply/forward blocks from an email body.

    Detects '-----Original Message-----' and '--- Forwarded by ... ---' separators,
    then parses embedded From/Sent/Subject headers from the block following each separator.

    Args:
        body: Plain text email body.

    Returns:
        List of QuotedReference models, ordered by position (0 = most recent quote).
    """
    if not body:
        return []

    # Find all separator positions
    separators: list[tuple[int, str]] = []
    for match in _ORIGINAL_MSG_SEP.finditer(body):
        separators.append((match.end(), "original"))
    for match in _FORWARDED_SEP.finditer(body):
        separators.append((match.end(), "forwarded"))

    if not separators:
        return []

    # Sort by position in the body (first separator = most recent quote)
    separators.sort(key=lambda x: x[0])

    refs: list[QuotedReference] = []
    for position, (sep_end, sep_type) in enumerate(separators):
        # Extract the header block after the separator (first ~500 chars is enough)
        block = body[sep_end:sep_end + 500]

        sender: str | None = None
        date_str: str | None = None
        subject: str | None = None

        if sep_type == "forwarded":
            # Lotus Notes format: "From: Name/Org on MM/DD/YYYY HH:MM AM"
            notes_match = _NOTES_FROM_RE.search(block)
            if notes_match:
                sender = _safe_decode(notes_match.group(1))
                date_str = _safe_decode(notes_match.group(2))
            # Also check for a Subject line after the forwarded header
            subj_match = _QUOTED_SUBJECT_RE.search(block)
            if subj_match:
                subject = _safe_decode(subj_match.group(1))
        else:
            # Outlook/Exchange format with From:/Sent:/Subject: lines
            from_match = _QUOTED_FROM_RE.search(block)
            if from_match:
                sender = _safe_decode(from_match.group(1))

            sent_match = _QUOTED_SENT_RE.search(block)
            if sent_match:
                date_str = _safe_decode(sent_match.group(1))

            subj_match = _QUOTED_SUBJECT_RE.search(block)
            if subj_match:
                subject = _safe_decode(subj_match.group(1))

        # Only create a reference if we extracted at least sender or subject
        if sender or subject:
            refs.append(QuotedReference(
                quoted_sender=sender,
                quoted_date=date_str,
                quoted_subject=subject,
                position=position,
            ))

    return refs


class EmailDatasetParser:
    """Initializes database with schema, parses MIME emails and MySQL employee data into SQLite."""

    def __init__(self, db_path: str) -> None:
        """Initialise parser with paths to the SQLite database and SQL schema.

        Args:
            db_path: Path where the SQLite database will be created/opened.
        """
        self.db_path = db_path
        self._conn: sqlite3.Connection | None = None

    @property
    def conn(self) -> sqlite3.Connection:
        """Return the active database connection, opening one if needed."""
        if self._conn is None:
            self._conn = sqlite3.connect(self.db_path)
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA foreign_keys=ON")
            self._conn.execute("PRAGMA synchronous=NORMAL")
            # Register custom SQL function for subject prefix stripping
            self._conn.create_function("_strip_subject", 1, lambda s: _strip_subject_prefixes(s) if s else None)
        return self._conn

    def close(self) -> None:
        """Close the database connection."""
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    def init_database(self) -> None:
        """Create all tables and indexes from the bundled SQL schema file."""
        schema_ref = resources.files("data_ingest").joinpath("schema.sql")
        schema_sql = schema_ref.read_text(encoding="utf-8")
        self.conn.executescript(schema_sql)
        logger.info("Database schema initialised at %s", self.db_path)

    def import_mime_emails(self, mime_directory: str, continue_mode: bool = False) -> ImportResult:
        """Walk the MIME directory tree, parse each email file, and insert into SQLite.

        Uses Python's email.parser.BytesParser with default policy for full MIME support.
        Files are processed in batches with periodic commits for memory efficiency.

        In continue mode, skips files whose source_path is already in the database.

        Args:
            mime_directory: Root directory containing the Enron maildir structure.
            continue_mode: If True, skip already-imported files based on source_path.

        Returns:
            ImportResult with counts of processed, succeeded, skipped, and failed emails.
        """
        mime_root = Path(mime_directory)
        if not mime_root.is_dir():
            raise FileNotFoundError(f"MIME directory not found: {mime_directory}")

        # Build set of already-imported source paths for continue mode
        imported_paths: set[str] = set()
        if continue_mode:
            cursor = self.conn.cursor()
            cursor.execute("SELECT source_path FROM message")
            imported_paths = {row[0] for row in cursor.fetchall()}
            logger.info("Continue mode: %d emails already imported, skipping those", len(imported_paths))

        byte_parser = BytesParser(policy=policy.default)
        total = 0
        success = 0
        skipped = 0
        plaintext_count = 0
        multipart_count = 0
        quoted_ref_count = 0
        errors: list[str] = []
        batch_size = 1000

        cursor = self.conn.cursor()

        for email_path in mime_root.rglob("*"):
            if not email_path.is_file():
                continue

            total += 1

            # Skip already-imported files in continue mode
            if continue_mode:
                relative_path = str(email_path.relative_to(mime_root))
                if relative_path in imported_paths:
                    skipped += 1
                    continue

            try:
                parsed = self._parse_mime_file(byte_parser, email_path, mime_root)
                self._insert_email(cursor, parsed)
                success += 1

                if parsed.content_type.startswith("multipart/"):
                    multipart_count += 1
                else:
                    plaintext_count += 1

                quoted_ref_count += len(parsed.quoted_references)
            except Exception as exc:
                error_msg = f"{email_path}: {exc}"
                errors.append(error_msg)
                logger.warning("Failed to parse %s: %s", email_path, exc)

            if total % batch_size == 0:
                self.conn.commit()
                logger.info(
                    "Processed %d emails (%d succeeded, %d skipped, %d errors)"
                    " [%d plaintext, %d multipart, %d quoted refs]",
                    total, success, skipped, len(errors),
                    plaintext_count, multipart_count, quoted_ref_count,
                )

        self.conn.commit()

        logger.info(
            "Email types: %d plaintext, %d multipart | Quoted references extracted: %d",
            plaintext_count, multipart_count, quoted_ref_count,
        )

        result = ImportResult(
            total_processed=total,
            success_count=success,
            skip_count=skipped,
            error_count=len(errors),
            errors=errors,
        )
        logger.info(
            "MIME import complete: %d processed, %d succeeded, %d skipped, %d errors",
            result.total_processed,
            result.success_count,
            result.skip_count,
            result.error_count,
        )
        return result

    def import_employee_data(self, mysql_dump_path: str) -> ImportResult:
        """Parse the MySQL dump's employeelist table and insert into employee + employee_email tables.

        Pivots Email_id/Email2/Email3/Email4 columns into employee_email bridge rows
        with is_primary flag.

        Args:
            mysql_dump_path: Path to the MySQL dump file (supports .gz compressed).

        Returns:
            ImportResult with counts of processed, succeeded, and failed employee records.
        """
        dump_path = Path(mysql_dump_path)
        if not dump_path.exists():
            raise FileNotFoundError(f"MySQL dump not found: {mysql_dump_path}")

        total = 0
        success = 0
        errors: list[str] = []

        employees = self._parse_mysql_employee_dump(dump_path)

        cursor = self.conn.cursor()
        for emp in employees:
            total += 1
            try:
                self._insert_employee(cursor, emp)
                success += 1
            except Exception as exc:
                error_msg = f"Employee {emp.email_primary}: {exc}"
                errors.append(error_msg)
                logger.warning("Failed to insert employee %s: %s", emp.email_primary, exc)

        self.conn.commit()

        result = ImportResult(
            total_processed=total,
            success_count=success,
            skip_count=0,
            error_count=len(errors),
            errors=errors,
        )
        logger.info(
            "Employee import complete: %d processed, %d succeeded, %d errors",
            result.total_processed,
            result.success_count,
            result.error_count,
        )
        return result

    def resolve_thread_references(self) -> int:
        """Post-import pass: attempt to resolve message_reference rows to actual message mids.

        Builds in-memory lookup indexes from the message table, then matches each unresolved
        reference by subject (stripped of Re:/Fwd: prefixes) and sender name. This avoids
        per-reference SQL queries entirely.

        Also backfills message.in_reply_to where it is NULL but a resolved parent exists.

        Returns:
            Count of successfully resolved references.
        """
        cursor = self.conn.cursor()

        # Build in-memory lookup: stripped_subject -> list of (mid, sender, sender_name)
        logger.info("Building in-memory message index for thread resolution...")
        cursor.execute("SELECT mid, subject, sender, sender_name FROM message")
        subject_index: dict[str, list[tuple[int, str, str | None]]] = {}
        msg_count = 0
        for mid, subject, sender, sender_name in cursor:
            msg_count += 1
            if subject:
                key = _strip_subject_prefixes(subject)
                if key:
                    subject_index.setdefault(key, []).append((mid, sender, sender_name))
        logger.info("Indexed %d messages (%d unique subjects)", msg_count, len(subject_index))

        # Get all unresolved references
        cursor.execute("""
            SELECT mref.mrid, mref.mid, mref.quoted_sender, mref.quoted_subject
            FROM message_reference mref
            WHERE mref.resolved_mid IS NULL
              AND (mref.quoted_subject IS NOT NULL OR mref.quoted_sender IS NOT NULL)
        """)
        unresolved = cursor.fetchall()
        logger.info("Resolving %d unresolved references...", len(unresolved))

        resolved_count = 0
        batch_size = 5000
        updates: list[tuple[int, int]] = []

        for i, (mrid, parent_mid, q_sender, q_subject) in enumerate(unresolved):
            resolved_mid = self._resolve_reference_inmem(
                subject_index, parent_mid, q_sender, q_subject
            )
            if resolved_mid is not None:
                updates.append((resolved_mid, mrid))
                resolved_count += 1

            if (i + 1) % batch_size == 0:
                if updates:
                    cursor.executemany(
                        "UPDATE message_reference SET resolved_mid = ? WHERE mrid = ?",
                        updates,
                    )
                    self.conn.commit()
                    updates = []
                logger.info(
                    "Reference resolution: %d/%d processed, %d resolved (%.0f%%)",
                    i + 1, len(unresolved), resolved_count,
                    resolved_count / (i + 1) * 100,
                )

        # Flush remaining updates and log final progress
        if updates:
            cursor.executemany(
                "UPDATE message_reference SET resolved_mid = ? WHERE mrid = ?",
                updates,
            )
        self.conn.commit()

        unresolved_remaining = len(unresolved) - resolved_count
        logger.info(
            "Reference resolution: %d/%d processed, %d resolved (%.0f%%)",
            len(unresolved), len(unresolved), resolved_count,
            resolved_count / max(len(unresolved), 1) * 100,
        )
        logger.info(
            "%d references unresolved (quoted email not in dataset — external senders or emails outside the Enron collection)",
            unresolved_remaining,
        )

        # Backfill in_reply_to where NULL using the most recent resolved reference (position 0)
        cursor.execute("""
            UPDATE message SET in_reply_to = (
                SELECT CAST(mr.resolved_mid AS TEXT)
                FROM message_reference mr
                WHERE mr.mid = message.mid
                  AND mr.resolved_mid IS NOT NULL
                  AND mr.position = 0
                LIMIT 1
            )
            WHERE in_reply_to IS NULL
              AND EXISTS (
                SELECT 1 FROM message_reference mr
                WHERE mr.mid = message.mid
                  AND mr.resolved_mid IS NOT NULL
                  AND mr.position = 0
              )
        """)
        backfilled = cursor.rowcount
        self.conn.commit()

        logger.info(
            "Thread resolution complete: %d in_reply_to backfilled from resolved references",
            backfilled,
        )
        return resolved_count

    @staticmethod
    def _resolve_reference_inmem(
        subject_index: dict[str, list[tuple[int, str, str | None]]],
        parent_mid: int,
        q_sender: str | None,
        q_subject: str | None,
    ) -> int | None:
        """Match a quoted reference against the in-memory subject index.

        Looks up by stripped subject first, then narrows by sender if possible.

        Args:
            subject_index: Pre-built mapping of stripped subjects to message records.
            parent_mid: The mid of the email containing this quote (to exclude).
            q_sender: Quoted sender string (may be display name, email, or mixed).
            q_subject: Quoted subject string.

        Returns:
            The mid of the matched message, or None if no confident match found.
        """
        if not q_subject:
            return None

        clean_subject = _strip_subject_prefixes(q_subject)
        if not clean_subject:
            return None

        candidates = subject_index.get(clean_subject)
        if not candidates:
            return None

        # Filter out self-reference
        candidates = [(mid, sender, sname) for mid, sender, sname in candidates if mid != parent_mid]
        if not candidates:
            return None

        # If only one candidate, return it
        if len(candidates) == 1:
            return candidates[0][0]

        # Multiple candidates — try to narrow by sender
        if q_sender:
            q_sender_lower = q_sender.strip().lower()
            # Extract a matchable token from the quoted sender (name or email prefix)
            sender_token = q_sender_lower.split("/")[0].split("@")[0].replace(",", "").strip()
            if sender_token:
                sender_matches = [
                    (mid, sender, sname)
                    for mid, sender, sname in candidates
                    if sender_token in sender.lower()
                    or (sname and sender_token in sname.lower())
                ]
                if sender_matches:
                    return sender_matches[0][0]

        # Fall back to the most recent candidate (last in list, highest mid)
        return candidates[-1][0]

    def validate(self) -> ValidationResult:
        """Validate imported data integrity: check referential consistency, flag missing fields.

        Returns:
            ValidationResult indicating overall validity and any issues found.
        """
        issues: list[str] = []
        cursor = self.conn.cursor()

        # Check message count
        cursor.execute("SELECT COUNT(*) FROM message")
        msg_count = cursor.fetchone()[0]
        if msg_count == 0:
            issues.append("No messages found in the database")

        # Check for messages missing message_id
        cursor.execute("SELECT COUNT(*) FROM message WHERE message_id IS NULL OR message_id = ''")
        empty_ids = cursor.fetchone()[0]
        if empty_ids > 0:
            issues.append(f"{empty_ids} messages have empty message_id")

        # Check for orphaned recipients (mid references non-existent message)
        cursor.execute(
            "SELECT COUNT(*) FROM recipient r LEFT JOIN message m ON r.mid = m.mid WHERE m.mid IS NULL"
        )
        orphaned_recipients = cursor.fetchone()[0]
        if orphaned_recipients > 0:
            issues.append(f"{orphaned_recipients} orphaned recipient rows (missing parent message)")

        # Check for orphaned thread references
        cursor.execute(
            "SELECT COUNT(*) FROM thread_reference t LEFT JOIN message m ON t.mid = m.mid WHERE m.mid IS NULL"
        )
        orphaned_refs = cursor.fetchone()[0]
        if orphaned_refs > 0:
            issues.append(f"{orphaned_refs} orphaned thread_reference rows")

        # Check for orphaned attachments
        cursor.execute(
            "SELECT COUNT(*) FROM attachment a LEFT JOIN message m ON a.mid = m.mid WHERE m.mid IS NULL"
        )
        orphaned_attachments = cursor.fetchone()[0]
        if orphaned_attachments > 0:
            issues.append(f"{orphaned_attachments} orphaned attachment rows")

        # Check employee_email consistency
        cursor.execute(
            "SELECT COUNT(*) FROM employee_email ee LEFT JOIN employee e ON ee.eid = e.eid WHERE e.eid IS NULL"
        )
        orphaned_emails = cursor.fetchone()[0]
        if orphaned_emails > 0:
            issues.append(f"{orphaned_emails} orphaned employee_email rows")

        # Check employee primary email exists in employee_email bridge
        cursor.execute("""
            SELECT COUNT(*) FROM employee e
            LEFT JOIN employee_email ee ON e.eid = ee.eid AND ee.is_primary = 1
            WHERE ee.eid IS NULL
        """)
        missing_primary = cursor.fetchone()[0]
        if missing_primary > 0:
            issues.append(f"{missing_primary} employees missing primary email in employee_email bridge")

        # Check for messages with has_attachments=1 but no attachment rows
        cursor.execute("""
            SELECT COUNT(*) FROM message m
            WHERE m.has_attachments = 1
            AND NOT EXISTS (SELECT 1 FROM attachment a WHERE a.mid = m.mid)
        """)
        phantom_attachments = cursor.fetchone()[0]
        if phantom_attachments > 0:
            issues.append(
                f"{phantom_attachments} messages flagged has_attachments=1 but have no attachment rows"
            )

        result = ValidationResult(is_valid=len(issues) == 0, issues=issues)
        logger.info("Validation %s: %d issues found", "passed" if result.is_valid else "failed", len(issues))
        return result

    # ── Private helpers ──────────────────────────────────────────────

    def _parse_mime_file(
        self, byte_parser: "BytesParser[StdEmailMessage]", file_path: Path, root_dir: Path
    ) -> EmailMessage:
        """Parse a single MIME file into an EmailMessage model.

        Args:
            byte_parser: Reusable BytesParser instance.
            file_path: Absolute path to the MIME file.
            root_dir: Root of the maildir tree (for relative source_path).

        Returns:
            Fully populated EmailMessage dataclass.
        """
        with open(file_path, "rb") as fh:
            msg = byte_parser.parse(fh)

        # Message-ID
        raw_message_id = msg.get("Message-ID", "")
        message_id = raw_message_id.strip() if raw_message_id else f"<generated-{file_path.name}@local>"

        # In-Reply-To
        raw_reply_to = msg.get("In-Reply-To", "")
        reply_to_ids = _extract_message_ids(raw_reply_to)
        in_reply_to = reply_to_ids[0] if reply_to_ids else None

        # Date
        date = self._parse_date(msg.get("Date", ""))

        # From
        sender_name_raw, sender_addr = parseaddr(msg.get("From", ""))
        sender = sender_addr.lower() if sender_addr else "unknown@unknown"
        sender_name = _safe_decode(sender_name_raw)

        # Subject
        subject = _safe_decode(msg.get("Subject"))

        # Recipients
        recipients: list[Recipient] = []
        for rtype, header_name in [("to", "To"), ("cc", "Cc"), ("bcc", "Bcc")]:
            raw_header = msg.get(header_name, "")
            if raw_header:
                for display_name, addr in getaddresses([raw_header]):
                    if addr:
                        recipients.append(
                            Recipient(
                                rtype=rtype,
                                address=addr.lower(),
                                display_name=_safe_decode(display_name),
                            )
                        )

        # Threading (References header)
        thread_references: list[ThreadReference] = []
        raw_references = msg.get("References", "")
        if raw_references:
            ref_ids = _extract_message_ids(raw_references)
            for position, ref_id in enumerate(ref_ids):
                thread_references.append(
                    ThreadReference(referenced_message_id=ref_id, position=position)
                )

        # Body extraction
        body_plain: str | None = None
        body_html: str | None = None
        charset: str | None = None
        attachments: list[Attachment] = []

        if msg.is_multipart():
            for part in msg.walk():
                part_ct = part.get_content_type()
                part_disposition = str(part.get("Content-Disposition", ""))

                if part_ct == "text/plain" and "attachment" not in part_disposition:
                    if body_plain is None:
                        body_plain = part.get_content()
                        charset = part.get_content_charset()
                elif part_ct == "text/html" and "attachment" not in part_disposition:
                    if body_html is None:
                        body_html = part.get_content()
                elif part.get_filename() or "attachment" in part_disposition:
                    att_content = part.get_payload(decode=True)
                    att_size = len(att_content) if att_content else None
                    attachments.append(
                        Attachment(
                            filename=part.get_filename(),
                            content_type=part_ct,
                            content_disposition=part_disposition if part_disposition else None,
                            size_bytes=att_size,
                            charset=part.get_content_charset(),
                        )
                    )
        else:
            ct = msg.get_content_type()
            if ct == "text/html":
                body_html = msg.get_content()
            else:
                body_plain = msg.get_content()
            charset = msg.get_content_charset()

        content_type = msg.get_content_type()

        # Enron-specific headers
        x_origin = _safe_decode(msg.get("X-Origin"))
        x_folder = _safe_decode(msg.get("X-Folder"))

        # Raw headers
        raw_headers = str(msg)
        header_end = raw_headers.find("\n\n")
        if header_end != -1:
            raw_headers = raw_headers[:header_end]

        source_path = str(file_path.relative_to(root_dir))

        # Parse quoted reply/forward blocks for thread reconstruction
        quoted_references = _parse_quoted_references(body_plain or "")

        return EmailMessage(
            message_id=message_id,
            in_reply_to=in_reply_to,
            date=date,
            sender=sender,
            sender_name=sender_name,
            subject=subject,
            body_plain=body_plain,
            body_html=body_html,
            content_type=content_type,
            charset=charset,
            x_origin=x_origin,
            x_folder=x_folder,
            source_path=source_path,
            has_attachments=len(attachments) > 0,
            raw_headers=raw_headers,
            recipients=recipients,
            thread_references=thread_references,
            attachments=attachments,
            quoted_references=quoted_references,
        )

    def _parse_date(self, raw_date: str) -> datetime:
        """Parse an email date header into a timezone-aware datetime.

        Args:
            raw_date: Raw date string from the email Date header.

        Returns:
            Timezone-aware datetime, falling back to UTC epoch on failure.
        """
        if not raw_date or not raw_date.strip():
            return datetime(2000, 1, 1, tzinfo=timezone.utc)
        try:
            return parsedate_to_datetime(raw_date)
        except Exception:
            logger.debug("Could not parse date: %s", raw_date)
            return datetime(2000, 1, 1, tzinfo=timezone.utc)

    def _insert_email(self, cursor: sqlite3.Cursor, email: EmailMessage) -> None:
        """Insert a parsed EmailMessage and its related rows into the database.

        Args:
            cursor: Active SQLite cursor.
            email: Fully parsed EmailMessage to insert.
        """
        cursor.execute(
            """INSERT OR IGNORE INTO message
               (message_id, in_reply_to, date, sender, sender_name, subject,
                body_plain, body_html, content_type, charset, x_origin, x_folder,
                source_path, has_attachments, raw_headers)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                email.message_id,
                email.in_reply_to,
                email.date.isoformat(),
                email.sender,
                email.sender_name,
                email.subject,
                email.body_plain,
                email.body_html,
                email.content_type,
                email.charset,
                email.x_origin,
                email.x_folder,
                email.source_path,
                1 if email.has_attachments else 0,
                email.raw_headers,
            ),
        )

        mid = cursor.lastrowid
        if mid is None or mid == 0:
            # INSERT OR IGNORE may skip duplicate message_id — retrieve existing mid
            cursor.execute("SELECT mid FROM message WHERE message_id = ?", (email.message_id,))
            row = cursor.fetchone()
            if row is None:
                return
            mid = row[0]

        for recipient in email.recipients:
            cursor.execute(
                "INSERT INTO recipient (mid, rtype, address, display_name) VALUES (?, ?, ?, ?)",
                (mid, recipient.rtype, recipient.address, recipient.display_name),
            )

        for ref in email.thread_references:
            cursor.execute(
                "INSERT INTO thread_reference (mid, referenced_message_id, position) VALUES (?, ?, ?)",
                (mid, ref.referenced_message_id, ref.position),
            )

        for att in email.attachments:
            cursor.execute(
                """INSERT INTO attachment (mid, filename, content_type, content_disposition, size_bytes, charset)
                   VALUES (?, ?, ?, ?, ?, ?)""",
                (mid, att.filename, att.content_type, att.content_disposition, att.size_bytes, att.charset),
            )

        for qref in email.quoted_references:
            cursor.execute(
                """INSERT INTO message_reference (mid, quoted_sender, quoted_date, quoted_subject, position)
                   VALUES (?, ?, ?, ?, ?)""",
                (mid, qref.quoted_sender, qref.quoted_date, qref.quoted_subject, qref.position),
            )

    def _parse_mysql_employee_dump(self, dump_path: Path) -> list[Employee]:
        """Parse the MySQL dump file and extract employee records using sqlglot.

        Reads the dump, extracts the employeelist table's CREATE and INSERT statements,
        transpiles them from MySQL to SQLite dialect via sqlglot, executes into a temporary
        in-memory database, then reads rows out into Employee models.

        Args:
            dump_path: Path to the MySQL dump file (supports .gz compressed).

        Returns:
            List of Employee models with email_addresses populated.
        """
        if dump_path.suffix == ".gz":
            with gzip.open(dump_path, "rt", encoding="utf-8", errors="replace") as fh:
                content = fh.read()
        else:
            content = dump_path.read_text(encoding="utf-8", errors="replace")

        # Extract the employeelist section (CREATE + INSERT) from the dump
        employee_sql = self._extract_table_sql(content, "employeelist")
        if not employee_sql:
            logger.warning("Could not find employeelist table in MySQL dump")
            return []

        # Transpile MySQL → SQLite via sqlglot
        sqlite_statements = sqlglot.transpile(employee_sql, read="mysql", write="sqlite")

        # Execute into a temporary in-memory database to read structured rows
        tmp_conn = sqlite3.connect(":memory:")
        try:
            # Create a compatible table manually — sqlglot's AUTO_INCREMENT translation
            # produces invalid SQLite syntax (UINT AUTOINCREMENT instead of INTEGER PRIMARY KEY)
            tmp_conn.execute("""
                CREATE TABLE employeelist (
                    eid INTEGER PRIMARY KEY,
                    firstName TEXT, lastName TEXT,
                    Email_id TEXT, Email2 TEXT, Email3 TEXT, EMail4 TEXT,
                    folder TEXT, status TEXT
                )
            """)
            for stmt in sqlite_statements:
                if stmt.strip().upper().startswith("CREATE"):
                    continue  # Skip the transpiled CREATE, we already made our own
                try:
                    tmp_conn.execute(stmt)
                except sqlite3.OperationalError as exc:
                    logger.debug("Skipping statement: %s (%s)", str(exc), stmt[:80])
            tmp_conn.commit()

            cursor = tmp_conn.execute(
                "SELECT eid, firstName, lastName, Email_id, Email2, Email3, EMail4, folder, status "
                "FROM employeelist"
            )
            rows = cursor.fetchall()
        finally:
            tmp_conn.close()

        employees: list[Employee] = []
        for row in rows:
            emp = self._row_to_employee(row)
            if emp is not None:
                employees.append(emp)

        logger.info("Parsed %d employees from MySQL dump via sqlglot", len(employees))
        return employees

    def _extract_table_sql(self, dump_content: str, table_name: str) -> str:
        """Extract CREATE TABLE and INSERT statements for a specific table from a MySQL dump.

        Filters out MySQL-specific directives (/*!...*/) and comments (#...).

        Args:
            dump_content: Full text content of the MySQL dump.
            table_name: Name of the table to extract.

        Returns:
            Concatenated SQL statements for the table, or empty string if not found.
        """
        lines = dump_content.split("\n")
        relevant_lines: list[str] = []
        in_target_section = False

        for line in lines:
            stripped = line.strip()

            # Skip MySQL directives and comments
            if stripped.startswith("/*!") or stripped.startswith("#"):
                continue

            # Detect start of target table section
            if re.search(rf"CREATE\s+TABLE.*`{table_name}`", stripped, re.IGNORECASE):
                in_target_section = True

            # Detect start of a different table's section (end of ours)
            if in_target_section and re.search(
                rf"CREATE\s+TABLE.*`(?!{table_name}`)\w+`", stripped, re.IGNORECASE
            ):
                break

            # Collect INSERT statements for the target table
            if re.search(rf"INSERT\s+INTO\s+`{table_name}`", stripped, re.IGNORECASE):
                in_target_section = True

            if in_target_section and stripped:
                relevant_lines.append(line)

        return "\n".join(relevant_lines)

    def _row_to_employee(self, row: tuple[int, str, str, str, str | None, str | None, str | None, str, str | None]) -> Employee | None:
        """Convert a raw SQLite row from the employeelist table into an Employee model.

        Pivots Email_id/Email2/Email3/Email4 into the email_addresses list.

        Args:
            row: Tuple of (eid, firstName, lastName, Email_id, Email2, Email3, EMail4, folder, status).

        Returns:
            Employee model or None if primary email is missing.
        """
        _, first_name, last_name, email_id, email2, email3, email4, folder, status = row

        email_primary = email_id.strip().lower() if email_id else ""
        if not email_primary:
            return None

        email_addresses = [EmployeeEmail(address=email_primary, is_primary=True)]
        for alt in [email2, email3, email4]:
            cleaned = _safe_decode(alt)
            if cleaned:
                alt_lower = cleaned.lower()
                if alt_lower != email_primary:
                    email_addresses.append(EmployeeEmail(address=alt_lower, is_primary=False))

        return Employee(
            first_name=_safe_decode(first_name),
            last_name=_safe_decode(last_name),
            email_primary=email_primary,
            folder=_safe_decode(folder),
            status=_safe_decode(status) if status and status != "N/A" else None,
            email_addresses=email_addresses,
        )

    def _insert_employee(self, cursor: sqlite3.Cursor, emp: Employee) -> None:
        """Insert an Employee and their email addresses into the database.

        Writes to both the employee table and employee_email bridge table,
        keeping email_primary in sync with the is_primary=1 bridge row.

        Args:
            cursor: Active SQLite cursor.
            emp: Employee model to insert.
        """
        cursor.execute(
            """INSERT OR IGNORE INTO employee (first_name, last_name, email_primary, folder, status)
               VALUES (?, ?, ?, ?, ?)""",
            (emp.first_name, emp.last_name, emp.email_primary, emp.folder, emp.status),
        )

        cursor.execute("SELECT eid FROM employee WHERE email_primary = ?", (emp.email_primary,))
        row = cursor.fetchone()
        if row is None:
            return
        eid: int = row[0]

        for email_addr in emp.email_addresses:
            cursor.execute(
                "INSERT OR IGNORE INTO employee_email (eid, address, is_primary) VALUES (?, ?, ?)",
                (eid, email_addr.address, 1 if email_addr.is_primary else 0),
            )
