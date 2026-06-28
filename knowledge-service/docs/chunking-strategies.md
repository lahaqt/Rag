# Chunking Strategy Options

This document records the production chunking rules for the knowledge service.
The starting point follows the document `第6小节：数据分块Chunk策略与实践.pdf`:

- Do not send whole documents directly to the model.
- Split after Tika text extraction and cleanup.
- Use `chunkSize` and `overlap` as core tuning parameters.
- Use `chunkSize=500` and `overlap=50` as the default starting point.
- Keep overlap around 10% to 25% of `chunkSize`.
- Prefer recursive chunking for general knowledge-base documents.
- Use hybrid routing when document types differ.

## Current Default

```yaml
rag:
  chunking:
    strategy: recursive
    chunk-size: 500
    chunk-overlap: 50
    min-chunk-size: 120
```

This is the recommended default for product manuals, policy documents, and
ordinary knowledge-base text. It tries large separators first, then smaller
separators:

```txt
blank line -> line break -> Chinese sentence punctuation -> English sentence punctuation -> comma -> space -> fixed character fallback
```

## Option A: Recursive Chunking

Use this as the baseline production strategy.

```yaml
rag:
  chunking:
    strategy: recursive
    chunk-size: 500
    chunk-overlap: 50
    min-chunk-size: 120
```

Applies to:

- product manuals
- enterprise policies
- help-center articles
- ordinary PDF, Word, and Markdown documents after Tika extraction

Tradeoff:

- Good semantic boundaries and low operational cost.
- Still depends on extracted text having usable paragraph or sentence
  separators.

## Option B: Overlap Chunking

Use this when the extracted text is noisy and separators are unreliable, such as
OCR text or messy PDF extraction.

```yaml
rag:
  chunking:
    strategy: overlap
    chunk-size: 400
    chunk-overlap: 80
    min-chunk-size: 100
```

Applies to:

- OCR output
- poorly formatted PDF text
- documents with broken line wrapping

Tradeoff:

- More robust at boundaries.
- More duplicate text, higher embedding/storage cost.

## Option C: Hybrid Chunking

Use this for enterprise knowledge bases with mixed document types.

```yaml
rag:
  chunking:
    strategy: hybrid
    chunk-size: 500
    chunk-overlap: 50
    min-chunk-size: 120
```

Current routing:

- `.log` files are grouped by lines.
- FAQ-style text using `Q:/A:` or `问:/答:` is grouped by question-answer pair.
- Other documents fall back to recursive chunking.

Applies to:

- mixed enterprise knowledge bases
- FAQ plus policy plus operational logs
- systems where document type can be inferred from filename or content pattern

Tradeoff:

- Better retrieval quality across mixed inputs.
- More rules must be maintained as document types grow.

## Option D: Semantic Chunking

This is not enabled in the current Java knowledge service yet. It should be added
only after the basic pipeline is stable.

Production shape:

```txt
Tika parse -> text cleanup -> sentence split -> embedding similarity -> topic-boundary split -> post-processing -> pgvector indexing
```

Applies to:

- legal documents
- financial compliance documents
- medical or safety-critical knowledge bases
- high-value documents where parsing/indexing cost is acceptable

Tradeoff:

- Highest chunk quality.
- Requires extra embedding or LLM calls, threshold tuning, and asynchronous
  processing.

## Tuning Rules

Start with:

```txt
chunkSize: 500
overlap: 50
```

Then tune by retrieval behavior:

- If results are too broad or contain unrelated topics, reduce `chunk-size` to
  `300` or `400`.
- If answers often miss context at chunk boundaries, increase `chunk-overlap` to
  15% to 25% of `chunk-size`.
- If many tiny chunks appear, increase `min-chunk-size`.
- If PDF text contains page headers, footers, URLs, or page numbers, improve
  cleanup before changing chunk parameters.
