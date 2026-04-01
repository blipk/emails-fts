## Code

---

## Parser Package (Python)

### Email Dataset Parser

#### Parsing class
```python
"""Parses MIME email files and MySQL employee dump into the SQLite database."""

import sqlite3
from email.parser import BytesParser
from email.policy import default as default_policy
from pathlib import Path


class EmailDatasetParser:
    """Initializes database with schema, parses MIME emails and MySQL employee data into SQLite."""

    def __init__(self, db_path: str) -> None: ...

    def init_database(self) -> None:
        """Create all tables and indexes from the bundled SQL schema file."""

    def import_mime_emails(self, mime_directory: str, continue_mode: bool = False) -> ImportResult:
        """Walk the MIME directory tree, parse each email file, and insert into SQLite.
        Uses Python's email.parser.BytesParser with default policy for full MIME support.
        Parses quoted reply/forward blocks from body_plain for thread reconstruction.
        In continue mode, skips files whose source_path is already in the database."""

    def import_employee_data(self, mysql_dump_path: str) -> ImportResult:
        """Parse the MySQL dump's employeelist table via sqlglot MySQL→SQLite transpilation.
        Pivots Email_id/Email2/Email3/Email4 columns into employee_email bridge rows with is_primary flag."""

    def resolve_thread_references(self) -> int:
        """Post-import pass: attempt to resolve message_reference rows to actual message mids.
        Matches quoted_sender+quoted_date+quoted_subject against the message table.
        Also backfills message.in_reply_to where it is NULL but a resolved parent exists.
        Returns count of resolved references."""

    def validate(self) -> ValidationResult:
        """Validate imported data integrity: check referential consistency, flag missing fields."""


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
```

#### Email and SQL table models (Python dataclasses)
```python
"""Email data models for MIME parsing and SQLite storage."""

from dataclasses import dataclass, field
from datetime import datetime


@dataclass
class Recipient:
    """A single email recipient with type classification."""
    rtype: str          # "to", "cc", or "bcc"
    address: str
    display_name: str | None = None


@dataclass
class ThreadReference:
    """A single entry from the References header chain."""
    referenced_message_id: str
    position: int       # 0 = oldest ancestor in the chain


@dataclass
class Attachment:
    """Metadata for a single MIME attachment (no binary content)."""
    filename: str | None
    content_type: str
    content_disposition: str | None  # "inline" or "attachment"
    size_bytes: int | None
    charset: str | None = None


@dataclass
class EmployeeEmail:
    """A single email address associated with an employee."""
    address: str
    is_primary: bool  # True if this is the canonical address (Email_id from the MySQL dump)


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
class QuotedReference:
    """A single quoted reply/forward block parsed from an email body."""
    quoted_sender: str | None
    quoted_date: str | None         # ISO 8601 when parseable, raw string otherwise
    quoted_subject: str | None
    position: int                   # 0 = most recent/innermost quote


@dataclass
class EmailMessage:
    """Complete parsed representation of a single MIME email."""
    message_id: str
    in_reply_to: str | None
    date: datetime                   # Timezone-aware
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
    raw_headers: str                 # Complete original MIME headers as-is
    recipients: list[Recipient] = field(default_factory=list)
    thread_references: list[ThreadReference] = field(default_factory=list)
    attachments: list[Attachment] = field(default_factory=list)
    quoted_references: list[QuotedReference] = field(default_factory=list)
```


---

## Backend Package (Kotlin / JVM)

### SQLite Database (datastore)

#### Database class
```kotlin
/** Base SQLite wrapper handling connection lifecycle, configuration, and core query execution. */
class Database(config: Configuration) {
    /** Initialize SQLite connection with WAL mode and memory-conscious settings. */
    fun connect(): Connection

    /** Close connection and release resources. */
    fun close()

    /** Execute a parameterized query and return results mapped via the provided mapper. */
    fun <T> query(sql: String, params: List<Any>, mapper: (ResultSet) -> T): List<T>

    /** Execute a parameterized update/insert statement. */
    fun execute(sql: String, params: List<Any>): Int
}
```

#### Table interfaces
```kotlin
/** Represents an email message record. */
data class MessageRecord(
    val mid: Int,
    val messageId: String,
    val inReplyTo: String?,
    val date: String,               // ISO 8601 with timezone offset
    val sender: String,
    val senderName: String?,
    val subject: String?,
    val bodyPlain: String?,
    val bodyHtml: String?,
    val contentType: String,
    val charset: String?,
    val xOrigin: String?,
    val xFolder: String?,
    val sourcePath: String,
    val hasAttachments: Boolean,
    val rawHeaders: String
)

/** Represents a recipient row. */
data class RecipientRecord(
    val rid: Int,
    val mid: Int,
    val rtype: String,              // "to", "cc", or "bcc"
    val address: String,
    val displayName: String?
)

/** Represents a threading reference row. */
data class ThreadReferenceRecord(
    val trid: Int,
    val mid: Int,
    val referencedMessageId: String,
    val position: Int
)

/** Represents an attachment metadata row. */
data class AttachmentRecord(
    val aid: Int,
    val mid: Int,
    val filename: String?,
    val contentType: String,
    val contentDisposition: String?,
    val sizeBytes: Int?,
    val charset: String?
)

/** Represents an employee directory row. */
data class EmployeeRecord(
    val eid: Int,
    val firstName: String?,
    val lastName: String?,
    val emailPrimary: String,
    val folder: String?,
    val status: String?
)

/** Represents an employee email bridge row. */
data class EmployeeEmailRecord(
    val eid: Int,
    val address: String,
    val isPrimary: Boolean
)
```


### Lucene Index (datastore)

#### Lucene class
```kotlin
/** Manages the Lucene index for full-text search over the email dataset. */
class LuceneIndex(config: Configuration) {
    /** Build or rebuild the full index from all emails in the database.
     *  Indexes: body_plain, subject, sender, recipient addresses (denormalised),
     *  attachment filenames (denormalised), date as LongPoint for range queries.
     *  Stores mid as the join key back to SQLite. */
    fun buildIndex(db: Database): IndexStats

    /** Search the index with the given parsed query, returning paginated document references. */
    fun search(query: Query, page: Int, pageSize: Int): LuceneSearchResult

    /** Find documents related to a given document using Lucene's MoreLikeThis. */
    fun findRelated(documentId: Int, maxResults: Int): LuceneSearchResult

    /** Close index reader/writer and release resources. */
    fun close()
}

data class IndexStats(val documentCount: Int, val indexSizeBytes: Long)

data class LuceneSearchResult(
    val hits: List<LuceneHit>,
    val totalHits: Long
)

data class LuceneHit(
    val emailId: Int,
    val score: Float,
    val highlightedFragments: Map<String, List<String>>
)
```


### Search

#### Configuration class
```kotlin
/** Application-wide configuration loaded from file or environment. */
data class Configuration(
    val sqlitePath: String,
    val luceneIndexPath: String,
    val defaultPageSize: Int,
    val maxPageSize: Int,
    val maxMemoryMb: Int,
    val fuzzyMatchDistance: Int,
    val apiPort: Int
)
```

#### Search class / search core
```kotlin
/** Core search service orchestrating queries across SQLite and Lucene with pagination. */
class SearchCore(db: Database, lucene: LuceneIndex, config: Configuration) {
    /** Execute a full search: parse input, query Lucene for relevance-ranked hits, fetch summary records from SQLite. */
    fun search(request: SearchRequest): SearchListResponse

    /** Fetch a single email with all relations (recipients, attachments, thread refs, employee). */
    fun getEmailDetail(emailId: Int): EmailDetail

    /** Reconstruct a full conversation thread from a given email's message_id.
     *  Walks in_reply_to + thread_reference to find all emails in the chain, ordered by date. */
    fun getThread(emailId: Int): ThreadResponse

    /** Find emails related to a specific email by content similarity. */
    fun findRelated(emailId: Int, maxResults: Int): SearchListResponse
}

data class SearchRequest(
    val rawQuery: String,
    val page: Int,
    val pageSize: Int,
    val sortBy: SortField,
    val sortOrder: SortOrder
)

/** Paginated list of search result summaries. */
data class SearchListResponse(
    val results: List<SearchResultSummary>,
    val totalHits: Long,
    val page: Int,
    val pageSize: Int,
    val queryParsedAs: String
)

/** Full conversation thread. */
data class ThreadResponse(
    val emails: List<EmailDetail>,
    val threadDepth: Int
)

enum class SortField { RELEVANCE, DATE, SENDER, SUBJECT }
enum class SortOrder { ASC, DESC }
```

#### Search class / input parser
```kotlin
/** Parses raw user input into Lucene queries, handling boolean operators and fuzzy matching. */
class SearchInputParser(config: Configuration) {
    /** Parse a raw query string into a Lucene Query object.
     *  Supports: AND/OR operators, quoted phrases, field-specific search (from:, to:, subject:, has:attachments),
     *  and automatic fuzzy expansion for misspellings. */
    fun parse(rawQuery: String): Query

    /** Return a human-readable representation of how the query was interpreted. */
    fun explain(rawQuery: String): String
}
```

#### Search class / exporter
```kotlin
/** Exports search results to various document formats. */
class SearchExporter {
    /** Export results to CSV format. */
    fun exportToCsv(results: List<EmailDetail>, outputStream: OutputStream)

    /** Export results to PDF with a formatted table. */
    fun exportToPdf(results: List<EmailDetail>, outputStream: OutputStream)

    /** Export results to DOCX with a formatted table. */
    fun exportToDocx(results: List<EmailDetail>, outputStream: OutputStream)
}
```

#### Search models/interfaces
```kotlin
/** Lightweight search result for paginated list views. */
data class SearchResultSummary(
    val mid: Int,
    val sender: String,
    val senderName: String?,
    val subject: String?,
    val date: String,
    val hasAttachments: Boolean,
    val recipientCount: Int,
    val employeeName: String?,              // Resolved name if sender is a known employee
    val employeeStatus: String?,            // Job title if known
    val relevanceScore: Float,
    val highlightedSubject: String,
    val highlightedBody: String,            // Snippet/fragment, not full body
    val matchedFields: List<String>
)

/** Full email detail with all relations. */
data class EmailDetail(
    val message: MessageRecord,
    val recipients: List<RecipientRecord>,
    val attachments: List<AttachmentRecord>,
    val threadReferences: List<ThreadReferenceRecord>,
    val employee: EmployeeRecord?,          // Null if sender is not a known employee
)
```


### CLI

#### CLI class
```kotlin
/** Command-line interface for administrative operations. */
class Cli(db: Database, lucene: LuceneIndex, config: Configuration) {
    /** Rebuild the Lucene index from all emails in the SQLite database. */
    fun rebuildIndex(): IndexStats

    /** Validate data integrity between SQLite and Lucene index. */
    fun validate(): CliValidationResult

    /** Print system status: database size, index document count, last import date. */
    fun status()
}

data class CliValidationResult(val isValid: Boolean, val issues: List<String>)
```


### API

#### API class
```kotlin
/** HTTP API exposing search functionality over the network. */
class Api(searchCore: SearchCore, exporter: SearchExporter, config: Configuration) {
    /** POST /search - Execute a search query and return paginated list results. */
    fun searchEndpoint(request: SearchRequestDto): SearchListResponseDto

    /** GET /email/{emailId} - Fetch full email detail with all relations. */
    fun emailDetailEndpoint(emailId: Int): EmailDetailDto

    /** GET /email/{emailId}/thread - Fetch the full conversation thread for an email. */
    fun threadEndpoint(emailId: Int): ThreadResponseDto

    /** GET /email/{emailId}/related - Find emails related to a specific email. */
    fun relatedEndpoint(emailId: Int, maxResults: Int): SearchListResponseDto

    /** POST /export/{format} - Export search results to the specified format (csv, pdf, docx). */
    fun exportEndpoint(request: SearchRequestDto, format: String): StreamedResponse

    /** GET /health - Health check endpoint. */
    fun healthEndpoint(): HealthDto
}
```

#### DTO Interfaces
```kotlin
/** Search request DTO for API transport. */
data class SearchRequestDto(
    val query: String,
    val page: Int = 1,
    val pageSize: Int = 20,
    val sortBy: String = "relevance",
    val sortOrder: String = "desc"
)

/** Paginated search list response DTO. */
data class SearchListResponseDto(
    val results: List<SearchResultSummaryDto>,
    val totalHits: Long,
    val page: Int,
    val pageSize: Int,
    val queryInterpretation: String
)

/** Lightweight search result summary for list views. */
data class SearchResultSummaryDto(
    val id: Int,
    val sender: String,
    val senderName: String?,
    val subject: String?,
    val date: String,                       // ISO 8601 with timezone
    val hasAttachments: Boolean,
    val recipientCount: Int,
    val employeeName: String?,              // Resolved employee name if sender is known
    val employeeStatus: String?,            // Job title/position if known
    val relevanceScore: Float,
    val highlightedSubject: String,
    val highlightedBody: String,            // Snippet/fragment, not full body
    val matchedFields: List<String>
)

/** Full email detail DTO with all relations. */
data class EmailDetailDto(
    val id: Int,
    val messageId: String,
    val inReplyTo: String?,
    val sender: String,
    val senderName: String?,
    val recipients: List<RecipientDto>,
    val subject: String?,
    val bodyPlain: String?,
    val bodyHtml: String?,
    val date: String,                       // ISO 8601 with timezone
    val contentType: String,
    val charset: String?,
    val xOrigin: String?,
    val xFolder: String?,
    val sourcePath: String,
    val hasAttachments: Boolean,
    val attachments: List<AttachmentDto>,
    val threadReferences: List<String>,     // Ordered list of referenced message IDs
    val rawHeaders: String,
    val employeeName: String?,
    val employeeStatus: String?
)

/** Conversation thread DTO. */
data class ThreadResponseDto(
    val emails: List<EmailDetailDto>,
    val threadDepth: Int
)

/** Recipient DTO. */
data class RecipientDto(
    val type: String,                       // "to", "cc", or "bcc"
    val address: String,
    val displayName: String?
)

/** Attachment metadata DTO. */
data class AttachmentDto(
    val filename: String?,
    val contentType: String,
    val sizeBytes: Int?
)

/** Health check DTO. */
data class HealthDto(
    val status: String,
    val indexedDocuments: Long,
    val databaseSizeBytes: Long
)
```


---

## Frontend Package (TypeScript / Vite)

### UI

#### Network wrapper class
```typescript
/** API client generated from OpenAPI spec, handling all network communication. */
const apiClient = {
  /** Execute a search query against the backend. */
  search: (request: SearchRequestDto): Promise<SearchListResponseDto> => { ... },

  /** Fetch full email detail with all relations. */
  getEmailDetail: (emailId: number): Promise<EmailDetailDto> => { ... },

  /** Fetch the full conversation thread for an email. */
  getThread: (emailId: number): Promise<ThreadResponseDto> => { ... },

  /** Fetch related emails for a given email ID. */
  fetchRelated: (emailId: number, maxResults: number): Promise<SearchListResponseDto> => { ... },

  /** Request an export and return a downloadable blob. */
  exportResults: (request: SearchRequestDto, format: ExportFormat): Promise<Blob> => { ... },

  /** Check backend health status. */
  health: (): Promise<HealthDto> => { ... },
}
```

#### UI components

##### Application
```typescript
/** Root application component, manages global state and routing. */
const App: FC = () => { ... }
```

##### Search input container
```typescript
/** Container for search input and configuration controls. */
const SearchInputContainer: FC = () => { ... }
```

###### Search box
```typescript
/** Main search input with interactive legend showing supported syntax (AND/OR, field:value, "phrases", fuzzy~). */
const SearchBox: FC<{ onSearch: (query: string) => void }> = () => { ... }
```

###### Search config
```typescript
/** Search configuration toggles: fuzzy matching on/off, sort field, sort order, page size. */
const SearchConfig: FC<{ config: SearchConfigState, onChange: (config: SearchConfigState) => void }> = () => { ... }
```

##### Search results container
```typescript
/** Container for search results display, email detail, thread view, and related emails. */
const SearchResultsContainer: FC = () => { ... }
```

###### Data view
```typescript
/** Filterable, sortable table/list displaying search result summaries with highlighted matches. */
const DataView: FC<{ results: SearchResultSummaryDto[], onSelectEmail: (id: number) => void }> = () => { ... }
```

###### Data view config
```typescript
/** Display configuration: toggle columns, switch between table/list view, density settings. */
const DataViewConfig: FC<{ config: DataViewConfigState, onChange: (config: DataViewConfigState) => void }> = () => { ... }
```

###### Email detail view
```typescript
/** Full email view with all metadata, recipients, attachments, and raw headers. */
const EmailDetailView: FC<{ emailId: number }> = () => { ... }
```

###### Thread view
```typescript
/** Conversation thread display showing all emails in a thread chain, ordered by date. */
const ThreadView: FC<{ emailId: number }> = () => { ... }
```

###### Related view
```typescript
/** Card-based display of emails related to the currently selected result. */
const RelatedView: FC<{ emailId: number, maxResults: number }> = () => { ... }
```
