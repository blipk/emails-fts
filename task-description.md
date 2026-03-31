Enron Email Data Spelunking Task

It's 2002. You are working as part of the US govt’s legal team on the Enron case, and you need to provide an efficient way to help the lawyers trawl through the email data to find relevant (incriminating) information.

The data

There are a number of versions of the Enron data set: 

-   SQL dump: http://www.ahschulz.de/enron-email-data/
-   CSV flat file: https://www.kaggle.com/datasets/wcukierski/enron-email-dataset
-   Flat MIME files: https://www.cs.cmu.edu/~enron/enron_mail_20150507.tar.gz

Pick any version you feel lets you get started quickly.

The tool

 Given the time constraints, the tool can be either:

-   A command-line app with a single argument: <search-text> that outputs results as text
-   A quick-and-dirty REST API with one endpoint. For example: POST /enron-data/search that returns results in JSON format
-   A simple UI of your choice with one textbox to enter the search terms

Input

A string containing a list of keywords to search for. The keywords can include misspellings and other artifacts, and can be specified in any order.

For bonus points, you should allow keywords to be logically grouped via and/or operators.

Output

A list of matching emails, referenced by their ID or file name, depending on the data set, plus the matched email text.

For bonus points, include emails that do not match the search query but instead are *related* by some metric to any email returned by the search query or the search query itself.

Constraints

-   Typically we request that candidates use a type-safe object oriented language and runtime of your choice (we speak Java, Kotlin, C/C++, C#).

-   You can preprocess the data and use a storage/indexing mechanism of your choice
-   You are permitted to leverage any database indexing and free-text search mechanism you like
-   Your tool must be able to be run within tight memory constraints (e.g. -Xmx256m in Java), effectively meaning you cannot load all the data into memory at once

What we want to see

-   Even though this is a time-constrained task, we want to see evidence of your ability to clearly structure and architect your code
-   We want to see evidence you have considered common issues that come into play with handling, indexing and searching a large body of unstructured text and some commentary as to what they are
-   While you won’t necessarily have the time to produce a truly performant solution, we want to see reasonable performance from the tool
-   Consider and comment how you would architect the tool to work over a much larger data set – gigabytes or petabytes of data
-   We want to see some commentary on your implementation and what you would improve if you were given more time to complete the task