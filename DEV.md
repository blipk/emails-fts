
# Development Process

## 1. Research & System Modelling

- started using my knowledge to analyze the task and begin to determine the required technologies and fundamental algorithms that will satisfy the stakeholder interests and system constraints
- further web research and AI discussion to expand on my knowledge
- made technical and system decisions, write up technical reasoning and layout system architecture using C4 model containers and components
- futher develop C4 system model with structurizr DSL and code templates: iterative LLM generation & human review of structurizr + code templates by prompting with task description, technical reasonings, and initial system model
- general review with LLM to improve container boundaries, relationships and minor gaps
- personally reviewed all structurizr DSL and finalised boundaries and relationships
- review of modeled code prototypes, checking all interfaces, improving comments
- decided the templated code prototypes were "good enough" as considering time constraints I will mostly use LLM agent code generation for the python email dataset parser as well as the frontend
- in the interest of learning and to demonstrate skills outside system modelling, the core Kotlin Search Server and all containers and components will be coded without LLM agents


## 2. Build Containers & Code

### Kotlin Search Server Package
- read kotlin and gradle getting started guides and standard library overviews
- set up kotlin development environment and initialize Kotlin package
- review planning documents and finalise class and data model interfaces before implementation
- try to find and read a lot of lucene docs, experimenting with the core concepts and their many variations (query parsers, indexing, analyzers, documents), also reading more Kotlin docs and concepts (elvis null coalescing operator, trailing lambdas, companion objects, `when` matcher, `Unit` type, trailing lambdas, data classes, extension functions, scope functions - let, apply, run, also, and with. `it`/`this` accessors, lambda with receiver, interface delegation, objects and data objects, `open` inheritable classes, `sealed` classes, `enum` classes, backing `field` keyword in get/set, extension and delegated properties, safe cast `as?`)
- create first functions for lucene index building and query parsing

### Python Email Data Set Parser
- curate planning context prompt with system model and prompt LLM agent and directive to create the python data processor.
    LLM issues:
    - as I didn't fully develop the code level specifications with full technical container requirements specification templates the agent tried to parse+convert the mysql syntax to sqlite using line splitting and regex, quick research and directed it to use the `sqlglot` python library
    - validation tests briefly reviewed but could be worked on further, a validation/diff of the final result against the complete mysql database could be useful
    - prompted to improve UX: tmp file management/location, CLI output clarity, clean/continue operating modes
    - could potentially use perf improvements but it's acceptable as is
- after reviewing the initial code and the generated database it mostly looks the same as the mysql dump as there were no relevant headers in the MIME email files (or most were plaintext) that could be used to extract attachments or thread info from
- inspected source data again and decided to reconstruct threads/attachments from quoted reply/forward blocks in email body text, same data as in the mysql dump `Referenceinfo` table, prompted LLM with instructions for parsing the blocks and then resolving threads from them, worked well and resolved threading reference but needed to be directed to improve performance and ensure schema integrity
- further analysis of the source emails reveals custom headers such as `X-FileName` that could be useful in reconstructing attachments, worked with LLM to investigate any other information that may be useful in a legal analysis context
- discovered plenty of useful metadata, mostly in the headers of original emails as well as quoted emails - mostly noted them down at this stage but decided to add headers and email references to relational schema for extra search, and implement extracting attachment information from identified lotus notes tags in email bodies seeing as there's none in the original email headers

### TypeScript Web Client
-

## 3. Testing & Debugging
- Build Indexes, Do Searches
- Iteratively develop features and container integrations
- Create more tests


## 4. Documentation, Packaging & Deployment
- ideally would set up podman/docker compose with caddy/nginx for a reverse proxy to serve the API and frontend build
- would probably make the Kotlin server serve the frontend build alongside the API, and provide a CLI script/arg that starts everything


### Kotlin/Maven/JVM annoyances
- no dependency management from gradle wrapper
- gradle init package has too much example comments
- searching for `lucene` on maven central shows outdated package, `lucene-core` is correct package and doesnt show on the first page
- the lucene docs are kind of terrible



###### TODO
- set up ktlint
- implement API
    - hardest part of this is managing DTO translation which could be complicated for pagination cursors as they would need to be serialized to the web client and/or server state would need to be created and synced with a client identifier
- implement the email threading routines
- implement kotlin app tests
- initial C4 model could definitely be improved
- there is no web client yet
