/**
 * This file contains the dataclass records that unwrap the ResultRow and transform them into application specific forms
 */

package dev.emailsfts.core.models

import org.jetbrains.exposed.v1.core.Table


/** Represents an email message record. */
data class MessageRecord(
    val mid: Int,
    val messageId: String,
    val inReplyTo: String?,             // MIME In-Reply-To header
    val inReplyToResolved: Int?,        // Backfilled FK to message(mid) from resolved quoted references
    val date: String,                   // ISO 8601 with timezone offset
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
    val rtype: String,                  // "to", "cc", or "bcc"
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
    val charset: String?,
    val source: String                  // "mime" = from MIME part, "body_reference" = from <<filename>> in body text
)

/** Represents a single email header row. */
data class EmailHeaderRecord(
    val hid: Int,
    val mid: Int,
    val name: String,
    val value: String,
    val position: Int
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

/** Represents a quoted reply/forward reference row (parsed from body_plain).
 *  resolved_mid is NULL when the quoted email is not in the dataset (external senders, etc). */
data class MessageReferenceRecord(
    val mrid: Int,
    val mid: Int,
    val quotedSender: String?,
    val quotedDate: String?,
    val quotedSubject: String?,
    val resolvedMid: Int?,              // FK to message(mid), NULL if unresolvable
    val position: Int                   // 0 = most recent/innermost quote
)