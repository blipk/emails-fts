# Enron Data
When Enron collapsed in 2001, about 500.000 internal emails were made public. There are already some publications based on the data (also to be found in the former link). Jitesh Shetty and Jafar Adibi cleaned the data and put it in a MySQL database. The dump is no longer online, but was created under MySQL 4 anyway and cannot be imported in MySQL 5 without changes in the dump-file. Additionally they tried to identify the former position of the employees.

I “repaired” the dump, found another list with the positions of the former employees and updated the old one. I also noticed that one person often has more than one email address. I switched through the mails and updated all addresses to a unique one, found under Email_id in table/dataframe employeelist. All replaced addresses are in the coloumns Email2 to Email4. I am not sure if the matching is totally correct or if I got all email addresses, so there is no guarantee for a 100% consistent dataset.

The data can be downloaded as a single sql-file and as a RData-file. The RData-file is simply an export from the database with some simple data management (changing data-types).

The tables/dataframes contain the following coloumns:

### Employeelist

eid: Employee-ID
firstName: First name
lastName: Last name
Email_id: Email address (primary). This one can be found in the other tables/dataframes and is useful for matching.
Email2: Additional email address that was replace by the primary one.
Email3: See above
Email4: See above
folder: The user’s folder in the original data dump.
status: Last position of the employee. “N/A” are unknown.

### Message

mid: Message-ID. Refers to the rows in recipientinfo and referenceinfo.
sender: Email address (updated)
date: Date.
message_id: Internal message-ID from the mailserver.
subject: Email subject
body: Email body. Can be truncated in the R-Version!
folder: Exact folder of the e-mail inclusing subfolders.

### Recipientinfo
(Note: If an email is sent to multiple recipients, there is a new row for every recipient!)

rid: Reference-ID
mid: Message-ID from the message-table/-dataframe
rtype: Shows if the reciever got the email normally (“to”), as a carbon copy (“cc”) or a blind carbon copy (“bcc”).
rvalue: The recipient’s email address.

### Referenceinfo

rfid: referenceinfo-ID
mid: Message-ID
reference: Contains the whole email with shortend headers.