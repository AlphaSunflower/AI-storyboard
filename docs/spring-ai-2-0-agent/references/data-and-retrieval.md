# Data And Retrieval

## ETL pipeline

Use the document ingestion abstractions as a pipeline: `DocumentReader` -> `DocumentTransformer` -> `DocumentWriter`/`VectorStore`. Preserve stable IDs and source metadata. Make chunk size, overlap, separators, and embedding model explicit and measurable.

```java
List<Document> documents = reader.get();
List<Document> chunks = transformer.transform(documents);
vectorStore.add(chunks);
```

Treat this as a conceptual pipeline: reader/transformer/writer types and method names must be checked against the selected 2.0.0 module and example.

## Embeddings and VectorStore

Use `EmbeddingModel` to create vectors and `VectorStore` for similarity search and writes. Keep embedding model dimensions consistent with the collection. Plan re-indexing when changing embedding models.

## Metadata filters

Use the portable SQL-like metadata filter expression supported by the selected store, and verify provider limitations. Always combine retrieval with authorization/tenant predicates; metadata filtering is not a replacement for access control.

Example expression shape (verify operator support for the store):

```text
tenant == 'acme' && documentType == 'policy' && year >= 2025
```

## Integrations

Spring AI supports stores such as PostgreSQL/PGVector, Redis, MongoDB Atlas, Neo4j, Elasticsearch/OpenSearch, Cassandra, Chroma, Milvus, Pinecone, Qdrant, Weaviate, Oracle, MariaDB, Couchbase, GemFire, Typesense, Azure Vector Search, and Amazon Bedrock Knowledge Base. Choose one integration and check its starter, schema/index setup, filter support, and operational requirements in the official docs.

## Retrieval quality

Test ingestion determinism, empty-result behavior, top-k, score thresholds, filters, duplicate chunks, prompt injection in documents, and source citation formatting.
