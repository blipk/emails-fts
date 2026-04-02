workspace "Enron Email FTS" "C4 diagrams for the Enron Email Full Text Search System" {

    model {
        properties {
            "structurizr.groupSeparator" "/"
        }

        # Actors
        legalAnalyst = person "Legal Analyst" "Searches and analyses Enron email data for case evidence."
        systemAdmin = person "System Admin" "Manages data import, indexing, and system configuration."

        # External Systems
        emailDataSet = softwareSystem "Email Data Set" "Raw Enron email data: MIME files and MySQL dump (for employee directory)." "External"

        # Software System
        emailsFts = softwareSystem "Enron Email FTS" "Provides full-text search with semantic and relational queries over the Enron email dataset." {

            # Containers

            group "Parser Package (Python)" {
                parser = container "Email Dataset Parser" "Parses MIME email files and MySQL employee dump into the SQLite database." "Python" {
                    parsingClass = component "Parsing Class" "Initializes database with schema, walks MIME directory tree, parses emails and inserts into SQLite. Also imports employee data from MySQL dump."
                    parserModels = component "Email and SQL Table Models" "Python dataclasses representing parsed email data and SQLite table structures (EmailMessage, Recipient, Attachment, ThreadReference, Employee, EmployeeEmail)."
                }
            }

            group "Backend Package (Kotlin, JVM)" {
                api = container "API" "HTTP API exposing search functionality over the network." "Kotlin / Ktor" {
                    apiClass = component "API Class" "HTTP endpoint handlers for search, email detail, thread view, related, export, and health."
                    dtos = component "DTO Interfaces" "Data transfer objects for API request/response serialization (list vs detail DTOs), synced with frontend via OpenAPI codegen."
                }

                searchService = container "Search Service" "Core search logic orchestrating queries across SQLite and Lucene." "Kotlin" {
                    configuration = component "Configuration" "Application-wide configuration loaded from file or environment."
                    searchCore = component "Search Core" "SQLite + Lucene wrapper for core search, email detail retrieval, thread reconstruction, and related search with pagination."
                    inputParser = component "Input Parser" "Parses raw user input into Lucene queries, handling boolean operators and fuzzy matching."
                    exporter = component "Exporter" "Exports search results to CSV, PDF, or DOCX formats."
                    searchModels = component "Search Models" "Search result data classes and interfaces (list summaries and full detail models)."
                }

                cli = container "CLI" "Command-line interface for administrative operations." "Kotlin" {
                    cliClass = component "CLI Class" "Entry point for admin commands: Lucene index rebuild, data validation, status checks."
                }

                group "Datastores" {
                    sqliteDb = container "SQLite Database" "Stores all email records, metadata, and employee directory." "SQLite" "Database" {
                        databaseClass = component "Database Class" "Base SQLite wrapper handling connection lifecycle, configuration, and core query execution."
                        tableInterfaces = component "Table Interfaces" "Kotlin interfaces/models representing database tables (Message, Recipient, ThreadReference, Attachment, Employee, EmployeeEmail)."
                    }

                    luceneIndex = container "Lucene Index" "Full-text search index over email content." "Apache Lucene" "Database" {
                        luceneClass = component "Lucene Class" "Manages index build, search execution, and MoreLikeThis related document queries."
                    }
                }
            }

            group "Frontend Package (TypeScript, Vite)" {
                ui = container "UI" "Web-based search interface for querying and viewing email results." "TypeScript / Vite" "Web Browser" {
                    appComponent = component "Application" "Root component managing global state and routing."
                    searchInputContainer = component "Search Input Container" "Container for search input and configuration controls."
                    searchBox = component "Search Box" "Main search input with interactive legend showing supported syntax (AND/OR, field:value, phrases, fuzzy)."
                    searchConfig = component "Search Config" "Search configuration toggles: fuzzy matching, sort field, sort order, page size."
                    searchResultsContainer = component "Search Results Container" "Container for search results display, email detail, thread view, and related emails."
                    dataView = component "Data View" "Filterable, sortable table/list displaying search results with highlighted matches."
                    dataViewConfig = component "Data View Config" "Display configuration: toggle columns, switch between table/list view, density settings."
                    emailDetailView = component "Email Detail View" "Full email view with all metadata, recipients, attachments, and raw headers."
                    threadView = component "Thread View" "Conversation thread display showing all emails in a thread chain."
                    relatedView = component "Related View" "Card-based display of emails related to the currently selected result."
                    networkWrapper = component "Network Wrapper" "Generated OpenAPI client handling all API communication."
                }
            }
        }

        # System Context relationships
        legalAnalyst -> emailsFts "Searches emails and exports results"
        systemAdmin -> emailsFts "Imports data, rebuilds indexes, configures system"
        emailsFts -> emailDataSet "Reads and imports raw email data from"

        # Container relationships
        legalAnalyst -> ui "Uses" "HTTPS"
        systemAdmin -> parser "Triggers data import" "CLI"
        systemAdmin -> cli "Triggers index rebuild and admin tasks" "CLI"
        ui -> api "Makes API requests" "HTTP/JSON"
        api -> searchService "Delegates search operations" "In-process"
        searchService -> sqliteDb "Reads email records" "Exposed"
        searchService -> luceneIndex "Queries full-text index" "Lucene API"
        cli -> searchService "Reads configuration" "In-process"
        cli -> luceneIndex "Rebuilds index" "Lucene API"
        luceneIndex -> sqliteDb "Reads emails for indexing" "Exposed"
        parser -> sqliteDb "Writes parsed email and employee data" "SQLite API"
        parser -> emailDataSet "Reads MIME files and MySQL dump" "File I/O"

        # Parser component relationships
        parsingClass -> parserModels "Creates instances of"
        parsingClass -> sqliteDb "Writes to" "SQLite API"

        # UI component relationships
        appComponent -> searchInputContainer "Renders"
        appComponent -> searchResultsContainer "Renders"
        searchInputContainer -> searchBox "Contains"
        searchInputContainer -> searchConfig "Contains"
        searchResultsContainer -> dataView "Contains"
        searchResultsContainer -> dataViewConfig "Contains"
        searchResultsContainer -> emailDetailView "Contains"
        searchResultsContainer -> threadView "Contains"
        searchResultsContainer -> relatedView "Contains"
        emailDetailView -> networkWrapper "Fetches full email via"
        threadView -> networkWrapper "Fetches thread chain via"
        searchBox -> networkWrapper "Triggers search via"
        searchConfig -> networkWrapper "Updates search params via"
        dataView -> networkWrapper "Fetches results via"
        relatedView -> networkWrapper "Fetches related emails via"
        networkWrapper -> api "Makes HTTP requests to" "HTTP/JSON"

        # API component relationships
        apiClass -> dtos "Serializes/deserializes using"
        apiClass -> searchCore "Delegates to"
        apiClass -> exporter "Delegates export to"

        # Search Service component relationships
        searchCore -> inputParser "Parses queries using"
        searchCore -> searchModels "Returns"
        searchCore -> luceneClass "Queries" "Lucene API"
        searchCore -> databaseClass "Fetches emails from" "Exposed"
        searchCore -> configuration "Reads config from"
        inputParser -> configuration "Reads fuzzy distance from"
        exporter -> searchModels "Reads results from"

        # CLI component relationships
        cliClass -> luceneClass "Triggers index rebuild via"
        cliClass -> configuration "Reads config from"

        # SQLite component relationships
        databaseClass -> tableInterfaces "Maps rows to"

        # Lucene component relationships
        luceneClass -> databaseClass "Reads emails for indexing from" "Exposed"
    }

    views {
        systemContext emailsFts "SystemContext" {
            include *
            autoLayout
        }

        container emailsFts "Containers" {
            include *
            autoLayout
        }

        component parser "Parser_Components" {
            include *
            autoLayout
        }

        component cli "CLI_Components" {
            include *
            autoLayout
        }

        component ui "UI_Components" {
            include *
            autoLayout
        }

        component api "API_Components" {
            include *
            autoLayout
        }

        component searchService "Search_Components" {
            include *
            autoLayout
        }

        component sqliteDb "SQLite_Components" {
            include *
            autoLayout
        }

        component luceneIndex "Lucene_Components" {
            include *
            autoLayout
        }

        styles {
            element "Person" {
                shape Person
                background #08427b
                color #ffffff
            }
            element "Software System" {
                background #1168bd
                color #ffffff
            }
            element "External" {
                background #999999
                color #ffffff
            }
            element "Container" {
                background #438dd5
                color #ffffff
            }
            element "Component" {
                background #85bbf0
                color #000000
            }
            element "Database" {
                shape Cylinder
            }
            element "Web Browser" {
                shape WebBrowser
            }
        }
    }

}
