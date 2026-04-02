"""Email data models for MIME parsing and SQLite storage."""

from dataclasses import dataclass, field
from datetime import datetime


@dataclass
class Recipient:
    """A single email recipient with type classification."""

    rtype: str  # "to", "cc", or "bcc"
    address: str
    display_name: str | None = None


@dataclass
class ThreadReference:
    """A single entry from the References header chain."""

    referenced_message_id: str
    position: int  # 0 = oldest ancestor in the chain


@dataclass
class Attachment:
    """Metadata for a single MIME attachment (no binary content)."""

    filename: str | None
    content_type: str
    content_disposition: str | None  # "inline" or "attachment"
    size_bytes: int | None
    charset: str | None = None
    source: str = "mime"  # "mime" = from MIME part, "body_reference" = from <<filename>> in body text


@dataclass
class EmailHeader:
    """A single email header name/value pair."""

    name: str
    value: str
    position: int  # Order in original headers (0-based)


@dataclass
class EmployeeEmail:
    """A single email address associated with an employee."""

    address: str
    is_primary: bool  # True if this is the canonical address


@dataclass
class Employee:
    """Employee directory entry. Imported from MySQL dump."""

    first_name: str | None
    last_name: str | None
    email_primary: str
    folder: str | None = None
    status: str | None = None
    email_addresses: list[EmployeeEmail] = field(default_factory=list)


@dataclass
class MessageReference:
    """A resolved quoted reply/forward reference row in the message_reference table.

    Note: Currently unused in the parser — QuotedReference is used during import and
    inserted directly into SQL. This model exists for completeness with the database
    schema. The Kotlin search server has its own equivalent data class.
    """

    mid: int
    quoted_sender: str | None
    quoted_date: str | None
    quoted_subject: str | None
    resolved_mid: int | None
    position: int


@dataclass
class QuotedReference:
    """A single quoted reply/forward block parsed from an email body."""

    quoted_sender: str | None
    quoted_date: str | None  # ISO 8601 when parseable, raw string otherwise
    quoted_subject: str | None
    position: int  # 0 = most recent/innermost quote


@dataclass
class EmailMessage:
    """Complete parsed representation of a single MIME email."""

    message_id: str
    in_reply_to: str | None
    date: datetime  # Timezone-aware
    sender: str
    sender_name: str | None
    subject: str | None
    body_plain: str | None
    body_html: str | None
    content_type: str
    charset: str | None
    x_origin: str | None
    x_folder: str | None
    source_path: str
    has_attachments: bool
    raw_headers: str  # Complete original MIME headers as-is
    recipients: list[Recipient] = field(default_factory=list)
    thread_references: list[ThreadReference] = field(default_factory=list)
    attachments: list[Attachment] = field(default_factory=list)
    quoted_references: list[QuotedReference] = field(default_factory=list)
    headers: list[EmailHeader] = field(default_factory=list)


@dataclass
class ImportResult:
    """Result of an import operation."""

    total_processed: int
    success_count: int
    skip_count: int
    error_count: int
    errors: list[str]


@dataclass
class ValidationResult:
    """Result of a data validation check."""

    is_valid: bool
    issues: list[str]
