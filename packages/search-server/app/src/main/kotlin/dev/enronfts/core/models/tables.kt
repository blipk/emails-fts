/**
 * Contains the Exposed `Table` inherited classes for the application.
 * Maps to the SQLite schema defined in planning/4-data-import-schema.md.
 */

package dev.emailsfts

import org.jetbrains.exposed.v1.core.Table

/** Core email message metadata. */
object Messages : Table("message") {
    val mid = integer("mid").autoIncrement()
    val messageId = text("message_id").uniqueIndex()
    val inReplyTo = text("in_reply_to").nullable()
    val inReplyToResolved = integer("in_reply_to_resolved").references(mid).nullable()
    val date = text("date")
    val sender = text("sender")
    val senderName = text("sender_name").nullable()
    val subject = text("subject").nullable()
    val bodyPlain = text("body_plain").nullable()
    val bodyHtml = text("body_html").nullable()
    val contentType = text("content_type")
    val charset = text("charset").nullable()
    val xOrigin = text("x_origin").nullable()
    val xFolder = text("x_folder").nullable()
    val sourcePath = text("source_path")
    val hasAttachments = integer("has_attachments").default(0)
    val rawHeaders = text("raw_headers")

    override val primaryKey = PrimaryKey(mid)
}

/** Recipients normalised per type, one row per recipient per message. */
object Recipients : Table("recipient") {
    val rid = integer("rid").autoIncrement()
    val mid = integer("mid").references(Messages.mid)
    val rtype = text("rtype")   // "to", "cc", or "bcc"
    val address = text("address")
    val displayName = text("display_name").nullable()

    override val primaryKey = PrimaryKey(rid)
}

/** Threading references from the MIME References header chain. */
object ThreadReferences : Table("thread_reference") {
    val trid = integer("trid").autoIncrement()
    val mid = integer("mid").references(Messages.mid)
    val referencedMessageId = text("referenced_message_id")
    val position = integer("position")

    override val primaryKey = PrimaryKey(trid)
}

/** Attachment metadata (not the binary content). */
object Attachments : Table("attachment") {
    val aid = integer("aid").autoIncrement()
    val mid = integer("mid").references(Messages.mid)
    val filename = text("filename").nullable()
    val contentType = text("content_type")
    val contentDisposition = text("content_disposition").nullable()
    val sizeBytes = integer("size_bytes").nullable()
    val charset = text("charset").nullable()
    val attachmentSource = text("source").default("mime")

    override val primaryKey = PrimaryKey(aid)
}

/** Normalized email headers, one row per header per message. */
object EmailHeaders : Table("email_header") {
    val hid = integer("hid").autoIncrement()
    val mid = integer("mid").references(Messages.mid)
    val name = text("name")
    val value = text("value")
    val position = integer("position")

    override val primaryKey = PrimaryKey(hid)
}

/** Quoted reply/forward references parsed from body_plain during import. */
object MessageReferences : Table("message_reference") {
    val mrid = integer("mrid").autoIncrement()
    val mid = integer("mid").references(Messages.mid)
    val quotedSender = text("quoted_sender").nullable()
    val quotedDate = text("quoted_date").nullable()
    val quotedSubject = text("quoted_subject").nullable()
    val resolvedMid = integer("resolved_mid").references(Messages.mid).nullable()
    val position = integer("position")

    override val primaryKey = PrimaryKey(mrid)
}

/** Employee directory imported from MySQL dump. */
object Employees : Table("employee") {
    val eid = integer("eid").autoIncrement()
    val firstName = text("first_name").nullable()
    val lastName = text("last_name").nullable()
    val emailPrimary = text("email_primary").uniqueIndex()
    val folder = text("folder").nullable()
    val status = text("status").nullable()

    override val primaryKey = PrimaryKey(eid)
}

/** Bridge table mapping employees to all their known email addresses. */
object EmployeeEmails : Table("employee_email") {
    val eid = integer("eid").references(Employees.eid)
    val address = text("address").uniqueIndex()
    val isPrimary = integer("is_primary").default(0)

    override val primaryKey = PrimaryKey(eid, address)
}
