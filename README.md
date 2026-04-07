# Email FTS - Full Text Search for Enron Email Database

This project is a full text relational and semantic search system for email files in the context of legal analysis, specifically for the widely available 2002 Enron email data set, although there are provisions and features to adapt to other email sources.

A full description of requirements can be found in [task-description.md](./task-description.md).

The motives were as a technical challenge and demonstration of my system architecture and software engineering skills.

### Usage Instructions

The data ingestion pipeline first needs to be run using `uv`, usage details and links to download the data files are in that packages [README.md](./packages/data-ingest/README.md).

Now you can build and run the web client and search server using gradle:

```bash
# navigate to the search-server package
cd ./packages/search-server/

# builds web client & search server
gradle build

# builds the Lucene index from the SQLite database from data ingestion
gradle run --args="--buildIndex"


# do a search on an input query, the CLI will offer pagination if there are many results
gradle run --args="--search='Search Query'"

# runs the search server and serves the web client (not yet implemented)
# gradle run --args="--run"
```

### Technical Architecture

The project consists of three packages:

- A python data ingestion parser to convert MIME email files into a relational SQLite database
- A kotlin search server that handles a full text search functionality using the SQLite database and Apache Lucene, it also has a RESTful HTTP interface to provide its functionality to the web frontend client
- A typescript web client for using the search service

The system was designed using the C4 Modelling System, as further explained in the next section.

These are C4 Model Diagrams representing the System Context and the complete System Containers and their relationships.

<details>
<summary>System Context Diagram</summary>

![System Context](./docs/c4diagrams/system-context.png "System Context")
</details>

<details>
<summary>System Containers Diagram</summary>

![System Containers](./docs/c4diagrams/system-containers.png "System Containers")
</details>

<p></p>

Here are diagrams of the individual System Containers & Components with relationships:

<details>
<summary>Search Service</summary>

![Search Service Container & Components](./docs/c4diagrams/search-service-components.png "Search Service Container & Components")
</details>

<details>
<summary>Email Data Ingestion</summary>

![Email Data Ingestion Container & Components](./docs/c4diagrams/data-ingestion-components.png "Email Data IngestionContainer & Components")
</details>

<details>
<summary>SQLite Database Store</summary>

![SQLite Database Store Container & Components](./docs/c4diagrams/sql-store-components.png "SQLite Database Store Container & Components")
</details>

<details>
<summary>Lucene Index Store</summary>

![Lucene Index Store Container & Components](./docs/c4diagrams/lucene-store-components.png "Lucene Index Store Container & Components")
</details>


<details>
<summary>Front-end Web Client UI</summary>

![Front-end Web Client UI Container & Components](./docs/c4diagrams/frontend-client-components.png "Front-end Web Client UI Container & Components")
</details>

### Development process

The task was analysed and researched then an appropriate system carefully planned using the C4 modelling system and structurizr DSL before being implemented with a mix of agentic coding (data ingestion + web client) and documentation reading and hand coding (main kotlin search server).

Documentation explaining and for the system architecture is in the [./planning/](./planning/) directory:

1.  [1-research-discussion.md](./planning/1-research-discussion.md)
    Summary of some of the initial research and LLM discussion on the task

2.  [2-my-architecture.md](./planning/2-my-architecture.md)
    Contains all my own thoughts and reasonings on a system architecture as well as a concrete beginnings on the C4 models Containers and Components

3.  [3-architecture-diagrams.dsl](./planning/3-architecture-diagrams.dsl)
    This contains the C4 model system architecture in the structurizr DSL format. This was created by me based on the initial containers and components description and then refined in an iterative process with an LLM agent.

4.  [4-data-import-schema.md](./planning/4-data-import-schema.md)
    Contains the data ingestion specifications, this file was iterated on with an LLM based on the structurizr DSL and used to produce the data ingestion script.

5.  [5-architecture-code.md](./planning/5-architecture-code.md)
    Contains prototype code specifications and interfaces for all C4 Containers and their Components, mostly generated in one shot based on C4 specification and then used as a reference in both agentic and non-agentic development.

There is also a more detailed development log and notes in [DEV.md](./DEV.md).


### Conclusions

Although this still requires some further work to become a production ready system, it covers all the core fundamentals of every component of the system, and I learnt a lot in the week I spent developing it.

Further Q&A on the system architecture and development state can be found in [QA.md](./QA.md).
