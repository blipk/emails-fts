# Enron Email Search Tool — Architecture Discussion

## Architecture Options

### Data Source Comparison

| Source | Format | Pros | Cons |
|--------|--------|------|------|
| **SQL dump** | MySQL dump | Pre-structured, easy SQLite import via conversion | Schema is fixed, may need MySQL→SQLite translation |
| **CSV (Kaggle)** | Single CSV | One file, simple parsing, quick start | Flat structure — all metadata in raw email text, ~1.3GB |
| **MIME files** | Raw .eml files | Richest data (headers, threading, attachments), most realistic | 1.7GB tarball, ~500k files, needs MIME parsing |

**Recommendation:** The SQL dump gets you running fastest with SQLite. The CSV is a close second. MIME files give the richest metadata (thread IDs, CC/BCC, attachments) which helps the "related emails" bonus but costs more parsing effort upfront.

---

### Architecture A: SQLite FTS5 — Pure SQLite

**Language:** C# (.NET) or Kotlin (JVM)
**Storage:** SQLite with FTS5 virtual table

1. **Preprocessing:** Stream-parse the data source → insert into SQLite table `emails(id, sender, recipients, date, subject, body)` + create an FTS5 virtual table over `subject` and `body`.
2. **Search:** FTS5 handles tokenized full-text search natively, supports AND/OR/NOT, phrase queries, prefix queries.
3. **Fuzzy matching:** FTS5 supports prefix matching (`term*`). For misspellings, add a trigram tokenizer or pre-generate a term dictionary and use edit-distance to expand query terms before hitting FTS5.
4. **Related emails:** Query by thread (same subject prefix, same sender/recipient pairs) or use FTS5 `bm25()` ranking to find emails with similar term vectors to matched results.

**Pros:**
- Single dependency — SQLite is embedded, no external services
- FTS5 is battle-tested, fast, and memory-efficient (indexes live on disk)
- Easily meets the 256MB constraint — data stays on disk, streamed in/out
- AND/OR operators are native FTS5 syntax
- BM25 ranking built-in

**Cons:**
- Fuzzy/misspelling support requires extra work (trigram index or query expansion)
- "Related emails" via term similarity is approximate — no semantic understanding

---

### Architecture B: Lucene-Based (Kotlin/JVM)

**Language:** Kotlin
**Storage:** Apache Lucene index (on disk)

1. **Preprocessing:** Parse data → build a Lucene index with fields for sender, recipients, date, subject, body.
2. **Search:** Lucene `QueryParser` with `BooleanQuery` for AND/OR. `FuzzyQuery` for misspelling tolerance (Levenshtein distance).
3. **Related emails:** Use `MoreLikeThis` query — feed it a matched document, it finds similar ones by term frequency.

**Pros:**
- Best-in-class full-text search — fuzzy matching, stemming, synonyms, all built-in
- `MoreLikeThis` is exactly what the "related emails" bonus asks for
- Memory-mapped index files — works within 256MB heap easily
- Wildcard, phrase, proximity queries for free

**Cons:**
- JVM only — rules out C# (unless using Lucene.NET, which lags behind at v4.8)
- Heavier dependency than SQLite
- Index is separate from storage — if you also want relational queries, you need SQLite alongside

---

### Architecture C: SQLite + Trigram Index (Fuzzy-First)

**Language:** C# or Kotlin
**Storage:** SQLite with FTS5 + a separate trigram table

Same as Architecture A, but adds a `trigrams(trigram TEXT, term TEXT)` table during preprocessing. At query time, decompose each search term into trigrams, look up candidate terms, rank by Jaccard similarity, then expand the FTS5 query with the top-N similar terms.

**Pros:**
- Stays in pure SQLite — no external dependencies
- Handles misspellings well (trigram matching is what PostgreSQL's `pg_trgm` uses)
- Composable with FTS5 for the final search

**Cons:**
- More preprocessing work
- Query expansion adds latency (two-phase search)
- Still weaker than Lucene for "related emails"

---

### Architecture D: PostgreSQL with pg_trgm + Full-Text Search

**Language:** C# or Kotlin
**Storage:** PostgreSQL

1. Import data into PostgreSQL. Create a `tsvector` column with GIN index for full-text search.
2. Use `pg_trgm` extension for fuzzy matching (`similarity()` function, trigram GIN index).
3. Use `ts_rank` for relevance scoring.

**Pros:**
- Fuzzy search + full-text search + relational queries all in one engine
- `pg_trgm` handles misspellings elegantly
- Mature, well-documented, production-grade

**Cons:**
- External service — not embedded like SQLite
- Heavier operational overhead
- Overkill if you don't need relational complexity beyond what SQLite provides

---

### Architecture E: Hybrid — SQLite Storage + Lucene Search (Kotlin) ← Chosen

**Language:** Kotlin
**Storage:** SQLite for relational data, Lucene for search index

1. Store emails in SQLite (structured queries, threading, metadata lookups).
2. Build a parallel Lucene index pointing back to SQLite row IDs.
3. Search hits Lucene → gets IDs → fetches full email text from SQLite.

**No FTS5** — Lucene completely replaces it. They solve the same problem. The responsibilities split cleanly:
- **SQLite** — stores raw data (sender, recipients, date, subject, body), handles structured queries, serves as the source of truth for full email content
- **Lucene** — handles all text search, fuzzy matching, ranking, MoreLikeThis

**Pros:**
- Best of both worlds — Lucene's search + SQLite's relational queries
- Both are embedded libraries — no servers to run
- Preprocessing writes to both in one pass
- Stays well within 256MB since both use memory-mapped disk-backed storage
- Can do complex metadata filtering (date ranges, specific senders) in SQLite, text search in Lucene

**Cons:**
- Two storage systems to keep in sync
- More complex preprocessing

---

### Recommendation Summary

| Priority | Best Architecture |
|----------|-------------------|
| **Get it done fast, C# preferred** | A: SQLite FTS5 |
| **Best search quality (fuzzy + related)** | B: Lucene (Kotlin) |
| **Best of both worlds, more effort** | E: Hybrid SQLite + Lucene (Kotlin) |
| **Fuzzy matching without Lucene** | C: SQLite + Trigrams |
| **Already have PostgreSQL running** | D: PostgreSQL |

---

## Deep Dive: Fuzzy Matching Approaches

### Trigram Index

A trigram is every 3-character substring of a word. E.g. `"energy"` → `ene`, `ner`, `erg`, `rgy`.

**How it works:**
- During preprocessing, decompose every unique word in the email corpus into trigrams and store in a lookup table: `trigrams(trigram TEXT, term TEXT)`
- At query time, decompose the search term into trigrams, look up which corpus terms share the most trigrams, rank by Jaccard similarity (intersection / union of trigram sets)
- Example: user types `"enrgy"` → trigrams `enr`, `nrg`, `rgy` → matches `"energy"` (shares `nrg`, `rgy`)
- Expand the FTS5 query with top-N similar terms

**Tradeoffs:**
- (+) Works well for typos, transpositions, missing characters
- (+) Pure SQLite — just another table
- (+) Preprocessing is one-time cost
- (-) Two-phase query adds latency
- (-) Trigram table can be large
- (-) No semantic variations

**Industry standard in:** Databases (PostgreSQL's `pg_trgm` is the canonical example)

### Query Expansion

Transform query terms at search time before sending to FTS5.

**How it works:**
- Build a dictionary of all unique terms during preprocessing
- At query time, compute edit distance (Levenshtein) against dictionary terms, pick closest matches within threshold (e.g. ≤ 2)
- Expand query: `"enrgy"` → `"enrgy" OR "energy"`

**Tradeoffs:**
- (+) Simpler preprocessing — just a word list
- (+) More intuitive to debug
- (-) Slower at query time — O(n × m) per term without optimization
- (-) Misses some typos that trigrams catch

**Industry standard in:** Search engines (Lucene, Elasticsearch, Solr) — but using Levenshtein automata, not brute-force dictionary comparison

### What Lucene Does

Lucene uses **query expansion via Levenshtein automata** — not trigrams:

1. Takes your term and a max edit distance (default 2)
2. Builds a finite state automaton that accepts all strings within N edits
3. Intersects this with the term index (FST) in a single pass — O(index size) regardless of edit distance
4. Expands query with matching terms, weighted by closeness

Trigrams are available in Lucene (`NGramTokenizer`) but serve a different purpose — partial substring matching / autocomplete, not misspelling tolerance.

---

## Deep Dive: BM25

BM25 (Best Matching 25) is a relevance ranking function that scores how well a document matches a query. FTS5 uses it via `bm25()`.

**Three factors:**

1. **Term frequency (TF):** More mentions = more relevant, but with diminishing returns. Controlled by parameter `k1` (typically 1.2).

2. **Inverse document frequency (IDF):** Rare terms are more informative. `IDF = log((N - n + 0.5) / (n + 0.5))` where N = total docs, n = docs containing the term.

3. **Document length normalization:** Short focused emails score higher than long emails with the same term count. Controlled by parameter `b` (typically 0.75).

**Formula (simplified):**

```
score = Σ IDF(term) × (TF × (k1 + 1)) / (TF + k1 × (1 - b + b × docLen/avgDocLen))
```

**In SQLite FTS5:**

```sql
SELECT *, bm25(emails_fts) AS rank
FROM emails_fts
WHERE emails_fts MATCH 'fraud AND accounting'
ORDER BY rank;
```

---

## Deep Dive: Lucene Index Structure

The Lucene index directory contains an **inverted index** — a mapping from every term to the list of documents containing it:

```
"fraud"    → [doc 4, doc 19, doc 882, doc 12041]
"energy"   → [doc 1, doc 4, doc 7, doc 19, ...]
"skilling" → [doc 44, doc 203]
```

**Files on disk:**

| File | Contents |
|------|----------|
| **Term dictionary** (`.tim`) | Sorted list of every unique term, compressed as an FST (finite state transducer) |
| **Postings lists** (`.doc`, `.pos`, `.pay`) | For each term: which documents contain it, at what positions, with what payloads (e.g. term frequency per doc) |
| **Stored fields** (`.fdt`, `.fdx`) | Original field values you chose to store (e.g. subject, sender) |
| **Norms** (`.nvd`, `.nvm`) | Document length normalization factors used by BM25 |
| **Segment info** (`.si`, `segments_*`) | Metadata about index segments (Lucene writes in immutable segments, merges periodically) |

**What's NOT in the index:**
- No trigrams (unless explicitly configured with NGramTokenizer)
- No precomputed edit distances — Levenshtein automaton is built on-the-fly at query time by traversing the term dictionary FST

---

## Key Decision: Hybrid Approach (Architecture E)

The hybrid uses **SQLite + Lucene, no FTS5**. Lucene replaces FTS5 entirely.

Lucene is an **embedded library**, not a service. No server, no ports, no daemon. It runs in-process.

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.apache.lucene:lucene-core:9.12.0")
    implementation("org.apache.lucene:lucene-queryparser:9.12.0")
    implementation("org.apache.lucene:lucene-analysis-common:9.12.0")
}
```

```kotlin
// Writing index
val directory = FSDirectory.open(Path.of("./lucene-index"))
val writer = IndexWriter(directory, IndexWriterConfig(StandardAnalyzer()))
writer.addDocument(doc)

// Searching index
val reader = DirectoryReader.open(directory)
val searcher = IndexSearcher(reader)
val results = searcher.search(query, 10)
```

**Query flow:**

```
User query → Lucene search → get matching doc IDs → fetch full emails from SQLite by ID
```

**Kotlin advantage:** Apache Lucene is a Java library. Kotlin runs on the JVM so you use it directly — no port, no wrapper, latest version (9.x). C# would need Lucene.NET which lags behind at v4.8.
