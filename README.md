# Storage API

A high-performance, **REST-only** file storage service that lets users upload, tag, list, rename, delete, and download files (from a few KB up to hundreds of GB). It returns an **unguessable, unique download link** after each successful upload, and enforces duplicate prevention by **filename** (per user) and **content** (per user). No UI, no session management; the client provides the user id with each request.

> Built with **Spring Boot**, **MongoDB**, **Gradle**, and packaged as a **Docker** image. Includes tests for concurrency, large uploads, and authorization edge cases.

---

## Table of Contents
- [Features](#features)
- [Non-Goals (scope clarifications)](#non-goals-scope-clarifications)
- [Architecture](#architecture)
- [Data Model & Indexes](#data-model--indexes)
- [API](#api)
- [Running locally](#running-locally)
- [Docker](#docker)
- [Configuration](#configuration)
- [Testing](#testing)
- [Operational notes](#operational-notes)
- [Roadmap / Points of consideration](#roadmap--points-of-consideration)
- [License](#license)

---

## Features
- **Upload** a file with:
  - `filename`, `visibility` (**PUBLIC** or **PRIVATE**), and up to **5 tags**. Tags are **case-insensitive** for matching (e.g., `TAG` == `tAg`).
- **Duplicate prevention** (per user):
  - Reject if **filename** already exists for the same user.
  - Reject if **content** (checksum) already exists for the same user.
  - Only per-user uniqueness; **no global** filename uniqueness.
- **Rename** a file (no reupload).
- **List** files:
  - All **PUBLIC** files.
  - All files for the **requesting user**.
  - Filter by **tag** (selected from existing tags), **sort** by filename, upload date, tag, content type, file size, and **paginate** results.
- **Download link**:
  - After successful upload, the service returns a **unique, unguessable** relative link. Both **PUBLIC and PRIVATE** files are downloadable via this link.
- **Content type detection** is performed after upload if not provided.

### Non-functional constraints (met by design)
- **No UI** — **REST API** only.
- **MongoDB** as database (metadata).
- **Container resource limits**: memory ~ **1 GB**, application disk usage **≤ 200 MB**. (Files are stored on a mounted **volume**, not the container layer.)
- **CI** builds a Docker image; README documents API and usage.

---

## Non-Goals (scope clarifications)
- **Authentication/authorization UI** is out of scope; the client **must supply the user id** with each request (e.g., `X-User-Id` header). The API enforces owner checks for rename/delete.
- **Antivirus scanning** is **not required**; the code exposes a `VirusScanner` port with a No-Op adapter so a scanner (e.g., clamd sidecar) can be added later without changing core logic.

---

## Architecture
**Hexagonal (ports & adapters)** for easy future swap of the storage backend (local FS now; S3, Google Drive, or GridFS later).

- **Domain / Application**
  - `UploadFileService`, `ListFilesService`, `RenameFileService`, `DeleteFileService`, `DownloadService`
  - Ports: `StoragePort` (streaming read/write/delete), `FileTypeDetector`, `VirusScanner` (No-Op), `ClockProvider`
- **Adapters**
  - `LocalFsStorageAdapter` — writes/reads streams to a **mounted volume** (not container FS).
  - Mongo repositories for `FileEntry` and `DownloadLink`.
  - MIME detection via Apache Tika.
- **Duplicate prevention**
  - Streaming **SHA-256** checksum while writing; unique indexes enforce per-user uniqueness; race-safe via “insert then catch duplicate-key” semantics.

---

## Data Model & Indexes

### `files` (Mongo collection)
```json
{
  "_id": "ObjectId",
  "userId": "string",
  "filename": "string",
  "checksum": "sha256-hex",
  "size": 123456789,
  "contentType": "application/pdf",
  "visibility": "PUBLIC|PRIVATE",
  "tags": ["lowercased","values"],
  "uploadedAt": "ISO-8601",
  "storageKey": "opaque key used by StoragePort implementation"
}
```

**Indexes**
- Unique: `{ userId: 1, filename: 1 }` — no same filename per user.
- Unique: `{ userId: 1, checksum: 1 }` — no same content per user.
- Query: `{ visibility: 1, uploadedAt: -1 }`, `{ tags: 1 }`, `{ contentType: 1 }`, `{ size: -1 }` for efficient listing/filters/sorting.

### `download_links`
```json
{
  "_id": "ObjectId",
  "token": "base64url-opaque",
  "fileId": "ObjectId",
  "createdAt": "ISO-8601"
}
```
- Unique: `{ token: 1 }`
- Links are **relative** (e.g., `/download/{token}`).

---

## API

### API contract (OpenAPI)

The service exposes its OpenAPI spec at runtime.

- **Spec file (in repo):** `src/main/resources/static/openapi.yml`
- **Runtime URL:** `/openapi.yml`
- **Local dev:** `http://localhost:8080/openapi.yml`

Quick check:
```bash
curl -s http://localhost:8080/openapi.yml | head
```

### Conventions
- **Headers**: `X-User-Id` (required for user-owned routes)
- **Download link**: `/download/{token}` (relative path).
- **Errors**: standard HTTP codes (`400/403/404/409/500`) with JSON error payload.

### Endpoints (summary)
- `POST /files` — Upload (multipart form)
- `GET /files/public` — List public files
- `GET /files/me` — List my files
- `PATCH /files/{id}/rename` — Rename (owner only)
- `DELETE /files/{id}` — Delete (owner only)
- `GET /download/{token}` — Download by secure token (works for PUBLIC and PRIVATE)

### Upload
```
POST /files
Headers:
  X-User-Id: <user-id>
Content-Type: multipart/form-data
Form fields:
  file: <binary>
  filename: <string>
  visibility: PUBLIC|PRIVATE
  tags: (up to 5) tag=val&tag=val...
```
**201 Created**
```json
{
  "id": "656d7f...",
  "filename": "report.pdf",
  "visibility": "PRIVATE",
  "tags": ["invoices","2025"],
  "size": 12345,
  "contentType": "application/pdf",
  "uploadedAt": "2025-09-18T09:00:00Z",
  "downloadLink": "/download/AbCDefGhIjKlMnOpQrStUv"
}
```

### List (public)
```
GET /files/public?tag=invoices&sort=-uploadedAt&page=0&size=20
```

### List (mine)
```
GET /files/me?tag=invoices&sort=filename&page=0&size=10
Headers:
  X-User-Id: <user-id>
```

### Rename
```
PATCH /files/{id}/rename
Headers:
  X-User-Id: <user-id>
Body:
  { "filename": "new-name.pdf" }
```
- `409 Conflict` if the new name already exists for this user.

### Delete
```
DELETE /files/{id}
Headers:
  X-User-Id: <user-id>
```
- `403 Forbidden` if not the owner.

### Download
```
GET /download/{token}
```
- `404` if token not found.

---

## Running locally

### Prereqs
- Java 21, Docker (optional), MongoDB (local or container)

### Gradle
```bash
# build & test
./gradlew clean test bootJar

# run with local Mongo
export MONGODB_URI="mongodb://localhost:27017/storage"
export STORAGE_PATH="$(pwd)/data/storage"
mkdir -p "$STORAGE_PATH"
java -jar build/libs/storage-api-*.jar
```

---

## Docker

### Build (multi-stage)
```bash
docker build -t storage-api:0.1.0 -f docker/Dockerfile .
```

### Run (enforcing the brief’s limits)
```bash
# create a host directory for binaries
mkdir -p $(pwd)/data/storage

docker run --rm -p 8080:8080   --memory=1g   --read-only   --tmpfs /tmp:rw,size=32m   -v $(pwd)/data/storage:/data/storage   -e STORAGE_PATH=/data/storage   -e MONGODB_URI='mongodb://host.docker.internal:27017/storage'   storage-api:0.1.0
```
- `--read-only` and mounting `/data/storage` ensure the application layer stays ≤ 200 MB while user files live on a volume.

---

## Configuration
| Env Var        | Default                              | Description |
|----------------|--------------------------------------|-------------|
| `MONGODB_URI`  | `mongodb://localhost:27017/storage`  | Mongo connection string |
| `STORAGE_PATH` | `/data/storage`                      | Filesystem base path for binary storage (mounted volume) |
| `SERVER_PORT`  | `8080`                               | HTTP port |

**Multipart limits** are set high in config to honor “no size limit”; uploads are streamed end-to-end.

---

## Testing

### What’s covered (maps to the checklist)
1. **Parallel upload: same filename** (same user) → one succeeds, one `409`.
2. **Parallel upload: same content** (same user) → one succeeds, one `409`.
3. **Large upload (~2GB) simulation** via a generated stream; verifies streaming and checksum without OOM.
4. **Delete not owner** → `403`.
5. **List all public** with paging/sorting/tag filter.

Run:
```bash
./gradlew test
```

---

## Operational notes
- **User id** is provided with requests; no user/session endpoints are exposed by this service.
- **Content type detection** happens **after upload** if not provided.
- **Download links** are **relative** to the service root (e.g., `/download/{token}`).

---

## Roadmap / Points of consideration
- **Bigger loads & more data**: switch `StoragePort` to **S3/MinIO**, enable **range requests**, shard Mongo collections, add **read replicas**, and cache hot metadata. Add **rate limiting** and **backpressure** on uploads.
- **Monitoring**: Micrometer → Prometheus (RPS, p95/p99 latency, 4xx/5xx), Mongo/FS health, storage usage, and download-token miss rates; Grafana dashboards + alerts.
- **Copy file**: metadata-only “copy” that points to the same blob (ref-counted), mint a **new download token**; if source deleted, blob persists until refcount hits zero. Different download links satisfied. (Also saves disk per “limited storage size”.)
- **Persistence improvements**: content-addressable storage with periodic **compaction** and orphan cleanup; checksum validation on read.
- **Versioning**: `FileEntry` with `versions[]` (each version has its own `storageKey`, size, checksum, createdAt); the stable `fileId` points to **current version**.
- **Resume upload**: chunked uploads with an `uploadId`, part numbers, and checksums; assemble on completion (TUS-like). Support ranged retries and integrity check before finalize.

---

## License
MIT (or your preferred OSS license).
