#!/usr/bin/env python3
"""Multi-format document ingestion pipeline → Qdrant via Ollama embeddings.

Supports incremental updates, directory watching, and multi-collection management.
"""

from __future__ import annotations

import argparse
import hashlib
import logging
import re
import signal
import sys
import threading
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Sequence

from qdrant_client import QdrantClient
from qdrant_client.models import (
    Distance,
    Filter,
    FieldCondition,
    MatchValue,
    PointStruct,
    VectorParams,
)
import requests as http_requests
from ollama import Client as OllamaClient

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
log = logging.getLogger("ingest")


def setup_logging(verbose: bool) -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter(
        "%(asctime)s  %(levelname)-7s  %(message)s", datefmt="%H:%M:%S"))
    log.addHandler(handler)
    log.setLevel(logging.DEBUG if verbose else logging.INFO)


# ---------------------------------------------------------------------------
# Retry
# ---------------------------------------------------------------------------

def retry(func: Any, *args: Any, max_retries: int = 3, base_delay: float = 1.0,
          _description: str = "", **kwargs: Any) -> Any:
    last_err: Exception | None = None
    for attempt in range(max_retries + 1):
        try:
            return func(*args, **kwargs)
        except Exception as exc:
            last_err = exc
            if attempt == max_retries:
                break
            delay = base_delay * (2 ** attempt)
            log.warning("Retry %d/%d in %.1fs: %s — %s",
                        attempt + 1, max_retries, delay, _description, exc)
            time.sleep(delay)
    raise last_err  # type: ignore[misc]


# ---------------------------------------------------------------------------
# Ollama helpers
# ---------------------------------------------------------------------------
ollama = OllamaClient()


def embed_batch(texts: Sequence[str], model: str) -> list[list[float]]:
    if not texts:
        return []
    resp = ollama.embed(model=model, input=list(texts))
    return resp["embeddings"]


def describe_image(path: Path, model: str, max_size: int = 1024) -> str | None:
    from PIL import Image
    img = Image.open(path)
    w, h = img.size
    if max(w, h) > max_size * 2:
        scale = max_size / max(w, h)
        img = img.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
    elif max(w, h) > max_size + 512:
        img = img.copy()
        img.thumbnail((max_size, max_size), Image.LANCZOS)

    try:
        resp = ollama.chat(
            model=model,
            messages=[{
                "role": "user",
                "content": (
                    "Describe this image in detail. Include what is depicted, "
                    "key objects, text if any, colors, layout, and context. "
                    "Return only the description, no preamble."
                ),
                "images": [str(path)],
            }],
        )
        return resp["message"]["content"]
    except Exception:
        log.warning("Vision model failed for %s, skipping", path.name)
        return None


# ---------------------------------------------------------------------------
# Text extractors
# ---------------------------------------------------------------------------

def read_text_safe(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        log.warning("UTF-8 decode failed for %s, falling back to latin-1", path.name)
        return path.read_text(encoding="latin-1", errors="replace")


def extract_markdown(path: Path) -> str:
    return read_text_safe(path)


def extract_txt(path: Path) -> str:
    return read_text_safe(path)


def extract_docx(path: Path) -> str:
    from docx import Document
    doc = Document(str(path))
    return "\n\n".join(p.text for p in doc.paragraphs if p.text.strip())


def extract_pptx(path: Path) -> str:
    from pptx import Presentation
    prs = Presentation(str(path))
    texts: list[str] = []
    for slide in prs.slides:
        for shape in slide.shapes:
            if shape.has_text_frame:
                texts.append(shape.text_frame.text)
    return "\n\n".join(texts)


def extract_html(path: Path) -> str:
    from bs4 import BeautifulSoup
    soup = BeautifulSoup(read_text_safe(path), "html.parser")
    for tag in soup(["script", "style", "nav", "footer", "header"]):
        tag.decompose()
    return soup.get_text("\n", strip=True)


def extract_epub(path: Path) -> str:
    """从 EPUB（ZIP + XHTML）中提取纯文本"""
    import zipfile
    from bs4 import BeautifulSoup
    texts: list[str] = []
    with zipfile.ZipFile(path) as zf:
        for name in sorted(zf.namelist()):
            if not name.startswith(".") and name.endswith((".xhtml", ".html", ".htm")):
                try:
                    soup = BeautifulSoup(zf.read(name), "html.parser")
                    for tag in soup(["script", "style", "nav", "footer", "header"]):
                        tag.decompose()
                    text = soup.get_text("\n", strip=True)
                    if text:
                        texts.append(text)
                except Exception:
                    log.warning("Skipping unreadable entry in epub: %s", name)
    return "\n\n".join(texts)


def extract_code(path: Path) -> str:
    return read_text_safe(path)


def extract_image(path: Path, vision_model: str, max_size: int) -> str | None:
    return describe_image(path, vision_model, max_size)


EXTRACTORS: dict[str, Any] = {
    ".md": extract_markdown, ".markdown": extract_markdown,
    ".txt": extract_txt,
    ".docx": extract_docx,
    ".pptx": extract_pptx,
    ".html": extract_html, ".htm": extract_html,
    ".epub": extract_epub,
}

CODE_EXTS = frozenset({
    ".py", ".js", ".ts", ".tsx", ".jsx", ".go", ".rs", ".java",
    ".c", ".cpp", ".h", ".hpp", ".rb", ".php", ".swift", ".kt",
    ".yaml", ".yml", ".toml", ".json", ".xml", ".csv",
})

IMAGE_EXTS = frozenset({".png", ".jpg", ".jpeg", ".gif", ".webp"})

SUPPORTED_EXTENSIONS = set(EXTRACTORS) | CODE_EXTS | IMAGE_EXTS

SKIP_DIRS = frozenset({"node_modules", ".venv", "__pycache__", ".git",
                        ".idea", ".vscode", ".claude"})


# ---------------------------------------------------------------------------
# Chunking
# ---------------------------------------------------------------------------

_SENTENCE_PAT = re.compile(r"(?<=[。！？.!?\n])\s*")
_CLAUSE_PAT = re.compile(r"(?<=[；;，,、])\s*")


def _split_recursive(text: str, max_chars: int) -> list[str]:
    """Split text at natural boundaries: sentence → clause → hard character cut.

    Each level tries to keep complete units together; only the last resort
    cuts mid-unit.
    """
    if len(text) <= max_chars:
        return [text]

    for pattern in (_SENTENCE_PAT, _CLAUSE_PAT):
        segments = [s.strip() for s in pattern.split(text) if s.strip()]
        if len(segments) <= 1:
            continue

        result: list[str] = []
        buf = ""
        for seg in segments:
            if buf and len(buf) >= max_chars:
                result.extend(_split_recursive(buf, max_chars))
                buf = ""
            if buf and len(buf) + len(seg) > max_chars:
                result.append(buf)
                buf = seg
            else:
                buf = (buf + seg) if buf else seg
        if buf:
            if len(buf) <= max_chars:
                result.append(buf)
            else:
                result.extend(_split_recursive(buf, max_chars))
        return result

    # Last resort: hard character cut
    return [
        text[i : i + max_chars].strip()
        for i in range(0, len(text), max_chars)
        if text[i : i + max_chars].strip()
    ]


def _merge_short(buf: str, candidate: str, chunks: list[str], max_chars: int) -> str:
    if len(candidate) <= max_chars:
        return candidate

    # Candidate exceeds max_chars.
    # Save accumulated buf as a complete chunk, then recursively split the rest.
    if buf:
        chunks.append(buf)
        rest = candidate[len(buf) :].strip("\n ")
    else:
        rest = candidate

    if not rest:
        return ""

    pieces = _split_recursive(rest, max_chars)
    for piece in pieces[:-1]:
        chunks.append(piece)
    return pieces[-1]


def chunk_by_headings(text: str, min_chars: int, max_chars: int) -> list[str]:
    sections = re.split(r"\n(?=## )", text)
    chunks: list[str] = []
    buf = ""
    for sec in sections:
        buf = (buf + "\n\n" + sec).strip() if buf else sec.strip()
        if len(buf) >= min_chars:
            buf = _merge_short("", buf, chunks, max_chars)
    if buf.strip():
        if chunks:
            chunks[-1] += "\n\n" + buf
        else:
            chunks.append(buf)
    return chunks


def chunk_by_paragraphs(text: str, min_chars: int, max_chars: int) -> list[str]:
    paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]
    chunks: list[str] = []
    buf = ""
    for para in paragraphs:
        candidate = (buf + "\n\n" + para).strip() if buf else para
        buf = _merge_short(buf, candidate, chunks, max_chars)
    if buf.strip():
        chunks.append(buf)
    return chunks


def chunk_sliding_window(text: str, min_chars: int, max_chars: int,
                         overlap: int) -> list[str]:
    chunks: list[str] = []
    start = 0
    while start < len(text):
        end = start + max_chars
        chunk = text[start:end].strip()
        if len(chunk) >= min_chars:
            chunks.append(chunk)
        elif chunks and chunk:
            chunks[-1] += "\n" + chunk
        start += max_chars - overlap
    return chunks


_FUNC_PATTERN = re.compile(
    r"^(\s*)(def |async def |function |class |func |"
    r"public (static )?(class |void |[A-Z])|private (static )?\w+ |protected (static )?\w+ )",
    re.MULTILINE,
)


def chunk_code(text: str, min_chars: int, max_chars: int, overlap: int) -> list[str]:
    boundaries = [m.start() for m in _FUNC_PATTERN.finditer(text)]
    if not boundaries:
        return chunk_sliding_window(text, min_chars, max_chars, overlap)

    chunks: list[str] = []
    buf = ""
    for i, start in enumerate(boundaries):
        end = boundaries[i + 1] if i + 1 < len(boundaries) else len(text)
        segment = text[start:end].strip()
        candidate = (buf + "\n\n" + segment) if buf else segment
        buf = _merge_short(buf, candidate, chunks, max_chars)
    if buf.strip():
        chunks.append(buf)
    return chunks


# ---------------------------------------------------------------------------
# File discovery
# ---------------------------------------------------------------------------

def file_hash(path: Path) -> str:
    return hashlib.md5(path.read_bytes()).hexdigest()


def _should_skip(parts: tuple[str, ...]) -> bool:
    return any(p in SKIP_DIRS or (p.startswith(".") and p not in (".", ".."))
               for p in parts)


def discover_files(root: Path) -> dict[Path, str]:
    result: dict[Path, str] = {}
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        if p.suffix.lower() not in SUPPORTED_EXTENSIONS:
            continue
        if _should_skip(p.parts):
            continue
        try:
            result[p] = file_hash(p)
        except OSError as exc:
            log.warning("Cannot read %s: %s", p, exc)
    return result


# ---------------------------------------------------------------------------
# Process a single file → chunks
# ---------------------------------------------------------------------------

def process_file(path: Path, args: argparse.Namespace) -> list[dict[str, Any]]:
    ext = path.suffix.lower()
    relative = str(path)
    fhash = file_hash(path)

    if ext in EXTRACTORS:
        text = EXTRACTORS[ext](path)
    elif ext in IMAGE_EXTS:
        text = extract_image(path, args.vision_model, args.image_max_size)
        if text is None:
            return []
    elif ext in CODE_EXTS:
        text = extract_code(path)
    else:
        return []

    if not text or not text.strip():
        return []

    if ext in (".md", ".markdown"):
        chunks = chunk_by_headings(text, args.chunk_min, args.chunk_max)
    elif ext in CODE_EXTS:
        chunks = chunk_code(text, args.chunk_min, args.chunk_max, args.chunk_overlap)
    else:
        chunks = chunk_by_paragraphs(text, args.chunk_min, args.chunk_max)

    if not chunks:
        chunks = chunk_sliding_window(text, args.chunk_min, args.chunk_max,
                                      args.chunk_overlap)

    results: list[dict[str, Any]] = []
    for i, chunk_text in enumerate(chunks):
        if len(chunk_text.strip()) < 30:
            continue
        results.append({
            "text": chunk_text,
            "metadata": {
                "file_path": relative,
                "file_name": path.name,
                "file_type": ext,
                "file_hash": fhash,
                "chunk_index": i,
            },
        })
    return results


# ---------------------------------------------------------------------------
# Qdrant operations
# ---------------------------------------------------------------------------

def qdrant_indexed_files(qdrant: QdrantClient, collection: str) -> dict[str, str]:
    """Return {file_path: file_hash} for every point in the collection."""
    indexed: dict[str, str] = {}
    offset: str | int | None = None
    while True:
        points, offset = qdrant.scroll(
            collection_name=collection,
            with_payload=["file_path", "file_hash"],
            with_vectors=False,
            limit=1000,
            offset=offset,
        )
        for pt in points:
            fp = pt.payload.get("file_path")
            fh = pt.payload.get("file_hash")
            if fp:
                indexed[str(fp)] = str(fh or "")
        if offset is None:
            break
    return indexed


def qdrant_indexed_detail(qdrant: QdrantClient, collection: str) -> dict[str, dict[str, Any]]:
    """Return {file_path: {hash, chunks, type}} for every indexed file."""
    detail: dict[str, dict[str, Any]] = defaultdict(lambda: {"hash": "", "chunks": 0, "type": ""})
    offset: str | int | None = None
    while True:
        points, offset = qdrant.scroll(
            collection_name=collection,
            with_payload=["file_path", "file_hash", "file_type"],
            with_vectors=False,
            limit=1000,
            offset=offset,
        )
        for pt in points:
            fp = pt.payload.get("file_path")
            if not fp:
                continue
            fp = str(fp)
            detail[fp]["hash"] = str(pt.payload.get("file_hash") or "")
            detail[fp]["chunks"] += 1
            detail[fp]["type"] = str(pt.payload.get("file_type") or "")
        if offset is None:
            break
    return dict(detail)


def delete_points_for_path(qdrant: QdrantClient, collection: str, file_path: str) -> None:
    qdrant.delete(
        collection_name=collection,
        points_selector=Filter(
            must=[FieldCondition(key="file_path", match=MatchValue(value=file_path))]
        ),
    )


def _safe_embed(chunk: dict[str, Any], model: str) -> list[dict[str, Any]]:
    """Embed a single chunk, recursively splitting if too long for the model."""
    text = chunk["text"]
    if not text.strip():
        return []
    try:
        vec = embed_batch([text], model=model)[0]
        return [{"chunk": chunk, "vectors": [vec]}]
    except Exception as exc:
        if "context length" not in str(exc).lower():
            raise
        if len(text) < 200:
            raise
        log.info("    splitting chunk (%d chars)", len(text))
        # Try paragraph boundary first, then hard split
        paragraphs = [p.strip() for p in text.replace("\r\n", "\n").split("\n\n") if p.strip()]
        if len(paragraphs) < 2:
            mid = len(text) // 2
            paragraphs = [text[:mid].strip(), text[mid:].strip()]
        result: list[dict[str, Any]] = []
        for para in paragraphs:
            sub = {"text": para, "metadata": {**chunk["metadata"]}}
            result.extend(_safe_embed(sub, model))
        return result


def upsert_chunks(qdrant: QdrantClient, collection: str, chunks: list[dict[str, Any]],
                  embed_model: str, batch_size: int, label: str,
                  vector_name: str | None = None,
                  meili_url: str | None = None,
                  meili_index: str | None = None) -> int:
    total = len(chunks)
    upserted = 0
    i = 0
    while i < len(chunks):
        batch = chunks[i:i + batch_size]
        texts = [c["text"] for c in batch]

        try:
            vectors = embed_batch(texts, model=embed_model)
        except Exception as exc:
            if "context length" in str(exc).lower():
                # Context-length errors are not transient — skip retries
                vectors = None
            else:
                try:
                    vectors = retry(embed_batch, texts, model=embed_model,
                                    _description=f"embed batch {i // batch_size + 1}")
                except Exception:
                    vectors = None

            if vectors is None:
                log.info("Batch failed — falling back to per-chunk embedding")
                vectors = []
                expanded: list[dict[str, Any]] = []
                for c in batch:
                    try:
                        embedded = _safe_embed(c, embed_model)
                        for entry in embedded:
                            vectors.extend(entry["vectors"])
                        expanded.extend(entry["chunk"] for entry in embedded)
                    except Exception as e:
                        log.error("Failed to embed chunk: %s — %s",
                                  c["metadata"]["file_path"], e)
                        continue
                batch = expanded
                i += len(expanded)
                if not vectors:
                    continue
        else:
            i += len(batch)

        def _build_vec(v):
            return {vector_name: v} if vector_name else v

        points = [
            PointStruct(
                id=hashlib.md5(c["text"].encode()).hexdigest(),
                vector=_build_vec(vec),
                payload={"text": c["text"], **c["metadata"]},
            )
            for c, vec in zip(batch, vectors)
        ]
        qdrant.upsert(collection_name=collection, points=points)
        upserted += len(points)
        log.info("[%s] upserted %d/%d", label, upserted, total)

    # --- Meilisearch ---
    if meili_url and meili_index:
        _index_to_meilisearch(meili_url, meili_index, chunks, label=label)

    return upserted


def ensure_collection(qdrant: QdrantClient, collection: str, embed_dim: int) -> None:
    if not qdrant.collection_exists(collection):
        qdrant.create_collection(
            collection_name=collection,
            vectors_config=VectorParams(size=embed_dim, distance=Distance.COSINE),
        )
        log.info("Created collection '%s'", collection)


# ---------------------------------------------------------------------------
# Meilisearch
# ---------------------------------------------------------------------------

def _meilisearch_doc(text: str, metadata: dict, meili_id: str) -> dict:
    return {
        "id": meili_id,
        "text": text,
        "file_path": metadata.get("file_path", ""),
        "file_name": metadata.get("file_name", ""),
        "file_type": metadata.get("file_type", ""),
        "file_hash": metadata.get("file_hash", ""),
        "chunk_index": metadata.get("chunk_index", 0),
    }


def _index_to_meilisearch(meili_url: str, index: str, chunks: list[dict[str, Any]],
                          label: str = "") -> None:
    """Push chunks into Meilisearch."""
    docs = [
        _meilisearch_doc(c["text"], c["metadata"],
                         hashlib.md5(c["text"].encode()).hexdigest())
        for c in chunks
    ]
    url = f"{meili_url}/indexes/{index}/documents"
    try:
        resp = http_requests.post(url, json=docs, timeout=30)
        resp.raise_for_status()
        log.info("[%s] meilisearch: indexed %d docs", label, len(docs))
    except Exception as exc:
        log.warning("[%s] meilisearch write failed (non-fatal): %s", label, exc)


def _delete_from_meilisearch(meili_url: str, index: str, file_path: str) -> None:
    """Delete all documents matching a file_path from Meilisearch."""
    url = f"{meili_url}/indexes/{index}/documents/delete"
    try:
        resp = http_requests.post(url, json={"filter": f'file_path = "{file_path}"'},
                                  timeout=10)
        resp.raise_for_status()
    except Exception as exc:
        log.warning("meilisearch delete failed (non-fatal): %s", exc)


def get_vector_name(qdrant: QdrantClient, collection: str) -> str | None:
    """Detect the vector name for an existing collection.

    Returns None for unnamed vectors, or the vector name string for named vectors.
    """
    info = qdrant.get_collection(collection)
    config = info.config.params.vectors
    if isinstance(config, dict):
        return next(iter(config.keys()))
    return None


# ---------------------------------------------------------------------------
# Commands: sync, list, list-collections, watch
# ---------------------------------------------------------------------------

def cmd_sync(root: Path, args: argparse.Namespace) -> None:
    """Full or incremental sync of a directory into a Qdrant collection."""
    disk_files = discover_files(root)
    log.info("Found %d files on disk", len(disk_files))

    # ---- Preview: collect chunks without touching Qdrant ----
    if args.dry_run and args.recreate:
        all_chunks: list[dict[str, Any]] = []
        with ThreadPoolExecutor(max_workers=args.parallel) as pool:
            futures = {pool.submit(process_file, fp, args): fp for fp in disk_files}
            for fut in as_completed(futures):
                fp = futures[fut]
                try:
                    chunks = fut.result()
                except Exception as exc:
                    log.error("Failed to process %s: %s", fp, exc)
                    continue
                if chunks:
                    all_chunks.extend(chunks)
        for i, c in enumerate(all_chunks[:10]):
            print(f"\n--- chunk {i} ({c['metadata']['file_name']}) ---")
            print(c["text"][:300])
        log.info("Would index %d chunks from %d files (dry-run, no changes made).",
                 len(all_chunks), len(disk_files))
        return

    # ---- Connect Qdrant ----
    try:
        qdrant = QdrantClient(url=args.qdrant_url)
    except Exception as exc:
        log.error("Cannot connect to Qdrant at %s: %s", args.qdrant_url, exc)
        sys.exit(1)

    if args.recreate:
        log.info("Recreating collection ...")
        qdrant.delete_collection(args.collection)
    ensure_collection(qdrant, args.collection, args.embed_dim)
    vector_name = get_vector_name(qdrant, args.collection)

    # ---- Full rebuild ----
    if args.recreate:
        all_chunks: list[dict[str, Any]] = []
        with ThreadPoolExecutor(max_workers=args.parallel) as pool:
            futures = {pool.submit(process_file, fp, args): fp for fp in disk_files}
            for fut in as_completed(futures):
                fp = futures[fut]
                try:
                    chunks = fut.result()
                except Exception as exc:
                    log.error("Failed to process %s: %s", fp, exc)
                    continue
                if chunks:
                    log.info("  %s → %d chunks", fp.relative_to(root), len(chunks))
                all_chunks.extend(chunks)

        log.info("Total: %d chunks from %d files", len(all_chunks), len(disk_files))

        if all_chunks:
            try:
                upsert_chunks(qdrant, args.collection, all_chunks,
                              args.embed_model, args.batch_size, "full",
                              vector_name=vector_name,
                              meili_url=args.meili_url, meili_index=args.collection)
            except Exception as exc:
                log.error("Embedding failed: %s", exc)
                log.info("Collection is empty. Fix the issue and re-run.")
                sys.exit(1)
        log.info("Done. %d chunks indexed.", len(all_chunks))
        return

    # ---- Incremental mode ----
    indexed_files = qdrant_indexed_files(qdrant, args.collection)
    disk_paths: set[str] = {str(p) for p in disk_files}

    to_index: list[Path] = []
    for fp, fh in disk_files.items():
        rel = str(fp)
        if rel not in indexed_files or indexed_files[rel] != fh:
            to_index.append(fp)

    to_delete: set[str] = set(indexed_files) - disk_paths

    log.info("New/changed: %d  Deleted: %d  Unchanged: %d",
             len(to_index), len(to_delete), len(disk_files) - len(to_index))

    if args.dry_run:
        for fp in to_index:
            rel = str(fp)
            chunks = process_file(fp, args)
            print(f"  + {rel} → {len(chunks)} chunks")
        for path in sorted(to_delete):
            print(f"  - {path}")
        return

    # Delete stale — safe, no embedding needed
    for path in sorted(to_delete):
        delete_points_for_path(qdrant, args.collection, path)
        log.info("  - deleted: %s", path)

    # Index new/changed — delete old AFTER successful upsert to avoid data loss
    total_added = 0
    failed: list[str] = []
    with ThreadPoolExecutor(max_workers=args.parallel) as pool:
        futures_map: dict[Any, Path] = {}
        for fp in to_index:
            futures_map[pool.submit(process_file, fp, args)] = fp

        for fut in as_completed(futures_map):
            fp = futures_map[fut]
            rel = str(fp)
            try:
                chunks = fut.result()
            except Exception as exc:
                log.error("Failed to process %s: %s", fp, exc)
                continue

            if not chunks:
                continue

            try:
                upsert_chunks(qdrant, args.collection, chunks,
                              args.embed_model, args.batch_size, rel,
                              vector_name=vector_name,
                              meili_url=args.meili_url, meili_index=args.collection)
            except Exception as exc:
                log.error("Embedding failed for %s: %s", rel, exc)
                failed.append(rel)
                continue

            # Only delete old points AFTER successful upsert
            if rel in indexed_files:
                delete_points_for_path(qdrant, args.collection, rel)
            total_added += len(chunks)

    if failed:
        log.warning("%d file(s) failed to embed, old vectors preserved: %s",
                    len(failed), ", ".join(failed))

    log.info("Done. %d chunks added/updated, %d files removed.",
             total_added, len(to_delete))


def cmd_list_collections(args: argparse.Namespace) -> None:
    """List all Qdrant collections with stats."""
    try:
        qdrant = QdrantClient(url=args.qdrant_url)
        collections = qdrant.get_collections().collections
    except Exception as exc:
        log.error("Cannot connect to Qdrant at %s: %s", args.qdrant_url, exc)
        sys.exit(1)

    if not collections:
        print("No collections found.")
        return

    for c in collections:
        info = qdrant.get_collection(c.name)
        count = info.points_count or 0
        detail = qdrant_indexed_detail(qdrant, c.name)
        print(f"\n{c.name}")
        print(f"  Chunks: {count}  |  Files: {len(detail)}")
        # Show by file type
        type_counts: dict[str, int] = defaultdict(int)
        for d in detail.values():
            type_counts[d["type"]] += 1
        for ft, n in sorted(type_counts.items()):
            print(f"    {ft or '(unknown)'}: {n} files")


def cmd_list(args: argparse.Namespace) -> None:
    """Show indexed files in a collection."""
    try:
        qdrant = QdrantClient(url=args.qdrant_url)
    except Exception as exc:
        log.error("Cannot connect to Qdrant at %s: %s", args.qdrant_url, exc)
        sys.exit(1)
    if not qdrant.collection_exists(args.collection):
        print(f"Collection '{args.collection}' does not exist.")
        return

    detail = qdrant_indexed_detail(qdrant, args.collection)
    if not detail:
        print(f"Collection '{args.collection}' is empty.")
        return

    print(f"\n{args.collection} — {sum(d['chunks'] for d in detail.values())} chunks, "
          f"{len(detail)} files\n")
    # Sort by path
    for path in sorted(detail):
        d = detail[path]
        print(f"  {d['chunks']:4d} chunks  [{d['type']}]  {path}")


def _index_one_file(qdrant: QdrantClient, path: Path, args: argparse.Namespace,
                    vector_name: str | None = None) -> None:
    """Index (or re-index) a single file into Qdrant."""
    if not path.is_file():
        return
    if path.suffix.lower() not in SUPPORTED_EXTENSIONS:
        return
    if _should_skip(path.parts):
        return

    rel = str(path)
    chunks = process_file(path, args)
    if chunks:
        # Upsert first, then delete old — avoids data loss if upsert fails
        upsert_chunks(qdrant, args.collection, chunks,
                      args.embed_model, args.batch_size, rel,
                      vector_name=vector_name,
                      meili_url=args.meili_url, meili_index=args.collection)
        delete_points_for_path(qdrant, args.collection, rel)
    else:
        log.info("  (no chunks) %s", rel)


def cmd_watch(root: Path, args: argparse.Namespace) -> None:
    """Watch a directory for changes and keep the Qdrant index in sync."""
    from watchdog.observers import Observer
    from watchdog.events import FileSystemEventHandler

    qdrant = QdrantClient(url=args.qdrant_url)
    ensure_collection(qdrant, args.collection, args.embed_dim)
    vector_name = get_vector_name(qdrant, args.collection)

    # Do a full initial sync
    log.info("Initial sync ...")
    cmd_sync(root, args)
    log.info("Watching for changes in %s (Ctrl+C to stop)", root)

    # Debounce: path → Timer
    pending: dict[str, threading.Timer] = {}
    lock = threading.Lock()

    def _schedule(action: str, path: str) -> None:
        with lock:
            if path in pending:
                pending[path].cancel()
            t = threading.Timer(2.0, _handle_event, args=[action, path])
            pending[path] = t
            t.start()

    def _handle_event(action: str, path: str) -> None:
        with lock:
            pending.pop(path, None)
        p = Path(path)
        if action == "deleted":
            delete_points_for_path(qdrant, args.collection, str(p))
            if args.meili_url:
                _delete_from_meilisearch(args.meili_url, args.collection, str(p))
            log.info("  - deleted: %s", path)
        else:
            _index_one_file(qdrant, p, args, vector_name=vector_name)

    class Handler(FileSystemEventHandler):
        def on_created(self, event: Any) -> None:
            if not event.is_directory:
                _schedule("created", event.src_path)

        def on_modified(self, event: Any) -> None:
            if not event.is_directory:
                _schedule("modified", event.src_path)

        def on_deleted(self, event: Any) -> None:
            if not event.is_directory:
                _schedule("deleted", event.src_path)

        def on_moved(self, event: Any) -> None:
            if not event.is_directory:
                _schedule("deleted", event.src_path)
                _schedule("created", event.dest_path)

    observer = Observer()
    observer.schedule(Handler(), str(root), recursive=True)
    observer.start()

    # Graceful shutdown
    stop_event = threading.Event()

    def _shutdown(signum: int, frame: Any) -> None:
        log.info("Stopping watcher ...")
        stop_event.set()

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    try:
        while not stop_event.is_set():
            stop_event.wait(1)
    finally:
        observer.stop()
        observer.join()
        with lock:
            for t in pending.values():
                t.cancel()
        log.info("Watcher stopped.")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Ingest documents into Qdrant")
    p.add_argument("directory", nargs="?", default=None,
                   help="Directory to scan (required for sync/watch)")

    g = p.add_argument_group("Qdrant")
    g.add_argument("--qdrant-url", default="http://localhost:16333",
                   help="Qdrant server URL")
    g.add_argument("--collection", "-c", default=None,
                   help="Qdrant collection name (default: directory basename)")
    g.add_argument("--embed-dim", type=int, default=768,
                   help="Embedding vector dimension")

    g = p.add_argument_group("Ollama models")
    g.add_argument("--embed-model", default="nomic-embed-text",
                   help="Ollama embedding model")
    g.add_argument("--vision-model", default="llava:latest",
                   help="Ollama vision model for images")

    g = p.add_argument_group("Chunking")
    g.add_argument("--chunk-min", type=int, default=200,
                   help="Minimum chunk size in characters")
    g.add_argument("--chunk-max", type=int, default=1500,
                   help="Maximum chunk size in characters")
    g.add_argument("--chunk-overlap", type=int, default=100,
                   help="Sliding window overlap in characters")

    g = p.add_argument_group("Performance")
    g.add_argument("--batch-size", type=int, default=32,
                   help="Embedding batch size")
    g.add_argument("--parallel", type=int, default=4,
                   help="Max parallel workers for file extraction")

    g = p.add_argument_group("Image")
    g.add_argument("--image-max-size", type=int, default=1024,
                   help="Resize images larger than this (px)")

    g = p.add_argument_group("Meilisearch")
    g.add_argument("--meili-url", default=None,
                   help="Meilisearch URL (e.g. http://localhost:7700, default: disabled)")
    g.add_argument("--meili-index", default=None,
                   help="Meilisearch index name (default: same as --collection)")

    g = p.add_argument_group("Modes")
    g.add_argument("--recreate", action="store_true",
                   help="Drop and recreate collection (full rebuild)")
    g.add_argument("--dry-run", action="store_true",
                   help="Preview changes without writing to Qdrant")
    g.add_argument("--watch", action="store_true",
                   help="Watch directory for changes and auto-sync")
    g.add_argument("--list", action="store_true",
                   help="List indexed files in a collection")
    g.add_argument("--list-collections", action="store_true",
                   help="List all collections with stats")
    g.add_argument("--verbose", "-v", action="store_true",
                   help="Enable debug logging")

    return p


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    args = build_parser().parse_args()
    setup_logging(args.verbose)

    # Auto-detect collection from directory name
    if args.collection is None:
        args.collection = "documents"

    # Default meili_index to collection name if not set
    if args.meili_url and not args.meili_index:
        args.meili_index = args.collection

    # Modes that don't need a directory
    if args.list_collections:
        return cmd_list_collections(args)

    if args.list:
        return cmd_list(args)

    # Modes that require a directory
    if not args.directory:
        print("Error: directory is required for sync/watch mode.")
        print("Use --list or --list-collections for collection info.")
        sys.exit(1)

    root = Path(args.directory).expanduser().resolve()
    if not root.is_dir():
        log.error("Not a directory: %s", root)
        sys.exit(1)

    # Auto-detect collection from directory basename if not explicitly set
    if args.collection == "documents":
        args.collection = root.name

    log.info("Collection: '%s'  |  Scanning %s ...", args.collection, root)

    if args.watch:
        return cmd_watch(root, args)

    return cmd_sync(root, args)


if __name__ == "__main__":
    main()
