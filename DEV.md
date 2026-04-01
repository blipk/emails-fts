
# Development Process

1. Research & System Modelling

- started using my knowledge to analyze the task and begin to determine the required technologies and fundamental algorithms that will satisfy the stakeholder interests and system constraints
- further web research and AI discussion to expand on my knowledge
- made technical and system decisions, write up technical reasoning and layout system architecture using C4 model containers and components
- futher develop C4 system model with structurizr DSL and code templates: iterative LLM generation & human review of structurizr + code templates by prompting with task description, technical reasonings, and initial system model
- general review with LLM to improve container boundaries, relationships and minor gaps
- personally reviewed all structurizr DSL and finalised boundaries and relationships
- review of modeled code prototypes, checking all interfaces, improving comments
- decided the templated code prototypes were "good enough" as considering time constraints I will mostly use LLM agent code generation for the python email dataset parser as well as the frontend
- in the interest of learning and to demonstrate skills outside system modelling, the core Kotlin Search Server and all containers and components will be coded without LLM agents


2. Build Containers & Code

- read kotlin and gradle getting started guides and standard library overviews
- set up kotlin development environment and initialize Kotlin package
- review planning documents and finalise class and data model interfaces before implementation


- curate planning context prompt with system model and prompt LLM agent and directive to create the python data processor.
    LLM issues:
    - as I didn't fully develop the code level specifications with full technical container requirements specification templates the agent tried to parse+convert the mysql syntax to sqlite using line splitting and regex, quick research and directed it to use the `sqlglot` python library
    - validation tests briefly reviewed but could be worked on further, a validation/diff of the final result against the complete mysql database could be useful
    - prompted to improve UX: tmp file management/location, CLI output clarity, clean/continue operating modes
    - could potentially use perf improvements but it's acceptable as is
- after reviewing the initial code and the generated database it mostly looks the same as the mysql dump as there were no relevant headers in the MIME email files (or most were plaintext) that could be used to extract attachments or thread info from
- inspected source data again and decided to reconstruct threads/attachments from quoted reply/forward blocks in email body text, same data as in the mysql dump `Referenceinfo` table, prompted LLM with instructions for parsing the blocks and then resolving threads from them, worked well and resolved threading reference but needed to be directed to improve performance (SQL -> in-memory)


3. Testing & Debugging
- Compute Indexes, Do Searches,



4. Documentation, Packaging & Deployment
- ideally would set up podman/docker compose with caddy/nginx for a reverse proxy to serve the API and frontend build
- will probably just make the Kotlin server serve the frontend build alongside the API, and provide a CLI script/arg that starts everything
- compile architecture diagrams
- complete README.md






# Libraries
- lucene
- exposed





### Diagram review
- technologies
- sqlite/lucene relationships
- codegen docstrings and function interfaces

