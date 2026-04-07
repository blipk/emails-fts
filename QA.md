# Q&A

Common issues I encountered while handling, indexing and searching large corpus of unstructured text?
- identifying whats useful knowledge information from the source data and metadata therein
- identifying and creating relationships between the discerned data information and metadata, and creating appropriate schemas
- optimizing data ingestion methods and operations (batching, system resource management)
- optimizing index building methods and operations
- researching the fundamental text analysis and indexing methods and mathematical formula/algorithms required for semantic full text search (n-gram indexes, Jaccard similarity, Levenshtein automaton / distances, edit distances, BestMatch25) and deciding whether to implement them vs using an external FTS solution/library - decided on Lucene as it incorporated the most of these fundamental methods and provided clean and configurable API
- considerations choosing and configuring different methods to index and search data for free text search: tokenization methods (text analyzers, which fields and how to store them), query parsing (input formats, filtering specifier formats, fuzzy matching distances and input term selection, weights on input terms) and querying methods
- data presentation layer

How would I scale to a much larger dataset (gigabytes/petabytes)?
- due to index size and system resource constraints at those scales this would require sharding over multiple lucine indexes with synchronized sharding of the relational SQLite database
- these are complicated systems to build, instead would probably use Elasticsearch and a postgresql/sqlite sharding solution
- might be better to consider document store engines over relational which have better support for distributed systems
- would require orchestration layer with request routing, load balancing, etc

How would I scale to multiple users?
- lucene-replicator for index replication across nodes
- also replication of sqlite database
- load balanced orchestartion via kubernetes
- application code changes for thread safety / event bus synchronization

What would I improve if given more time to complete the task?
- spend more time in the planning and analysis stage and data analysis on the email format to prevent schema changes during development
- investigate other forensic email analysis tools in relation to the prior point
- test development
- lucene indexing and querying configuration
- end user client usability
