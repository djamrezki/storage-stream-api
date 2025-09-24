# Storage API (WebFlux)

A high-throughput, **REST-only**, **reactive** file storage service built on **Spring WebFlux** and **Reactive MongoDB GridFS**. It lets users upload, tag, list, rename, delete, and download files—from a few KB to hundreds of GB—**without buffering files in memory**. After a successful upload, the API returns an **unguessable download link**. Duplicate prevention is enforced per user by **filename** and **content**.

> Tech: **Spring Boot (WebFlux)**, **Reactive MongoDB + GridFS**, **Gradle**, **Docker**. Includes tests for concurrency, large uploads, and authorization/ownership rules.

---

## Table of Contents
- [Features](#features)
- [Non-Goals](#non-goals)
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
  - `filename`, `visibility` (**PUBLIC** or **PRIVATE**), and up to **5 tags** (matching is **case-insensitive**).
- **Duplicate prevention** (per user):
  - Reject if **filename** already exists.
  - Reject if **content** (SHA-256) already exists.
- **Rename** a file (no re-upload).
- **List** files (reactive streams, page/size semantics):
  - All **PUBLIC** files.
  - All files belonging to **me** (the requesting user).
  - Filter by **tag**, sort by filename, upload date, tag, content type, or file size; **paginate** with `page`/`size`.
- **Download link**:
  - After upload, receive a **unique, unguessable** relative link.
  - Both **PUBLIC** and **PRIVATE** files are downloadable via the link.
- **Content type detection** happens after upload if not provided.

### Non-functional constraints
- **No UI** — **REST API** only.
- **MongoDB** used for metadata **and** binary storage (**GridFS**).
- **Container limits** respected: memory ≈ **1 GB**; application layer disk ≤ **200 MB** (blobs live in Mongo, not the image layer).
- CI builds a Docker image; README documents API & usage.

---

## Non-Goals
- No auth UI/endpoints; the client **must supply the user id** with each request (e.g., `X-User-Id` header). Ownership enforced on rename/delete.
- No antivirus scanning required; a port is defined so a scanner can be plugged later without changing core logic.

---

## Architecture
**Hexagonal (ports & adapters)**, fully reactive.

- **Domain / Application (reactive services)**
  - `ReactiveUploadService`, `DeleteFileServiceReactive`, `DownloadServiceReactive`
  - Ports:  
    - `ReactiveStoragePort` (streaming save/open/delete to blob store)  
    - `FileEntryQueryPort`, `DownloadLinkQueryPort`
- **Adapters**
  - **Storage**: `GridFsReactiveAdapter` — streams to **MongoDB GridFS** (no temp files, no body aggregation).
  - **Persistence**: Reactive repositories for `FileEntry` and `DownloadLink`.
  - **Web**: WebFlux controllers return `Mono`/`Flux` and stream responses.
- **Duplicate prevention**
  - Streaming **SHA-256** computed during upload; per-user unique indexes on filename and content checksum; race-safe via “insert then catch duplicate-key”.

---

## Data Model & Indexes

### `files` (Mongo collection)
```json
{
  "_id": "string",
  "ownerId": "string",
  "filename": "string",
  "filenameLc": "string",
  "contentSha256": "hex-string",
  "size": 123456789,
  "contentType": "application/pdf",
  "visibility": "PUBLIC|PRIVATE",
  "tags": ["lowercased","values"],
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601",
  "gridFsId": "string"
}
```

**Indexes**
- Unique: `{ ownerId: 1, filenameLc: 1 }`
- Unique: `{ ownerId: 1, contentSha256: 1 }`
- Query helpers: `{ visibility: 1, createdAt: -1 }`, `{ tags: 1 }`, `{ contentType: 1 }`, `{ size: -1 }`

### `download_links`
```json
{
  "_id": "string",
  "token": "base64url-opaque",
  "fileId": "string",
  "createdAt": "ISO-8601",
  "expiresAt": "ISO-8601|null"
}
```
- Unique: `{ token: 1 }`
- Optional TTL on `expiresAt`.

---

## API

### Conventions
- **Headers**: `X-User-Id` is **required** for user-owned routes.
- **Download link**: **`/files/download/{token}`** (note the `/files` prefix in this WebFlux version).
- **Errors**: Standard HTTP (`400/403/404/409/500`) with a JSON error payload.

### Sorting & Paging
- Query params:  
  - `page` (default `0`), `size` (default `10`)  
  - `sort` accepts one of: `filename`, `uploadedAt|createdAt|date`, `tag`, `contentType|type`, `size`  
    Use optional suffix `:asc` / `:desc` (e.g., `sort=uploadedAt:desc`). If omitted, most lists default to `uploadedAt desc`.

### Endpoints (summary)
- `POST /files` — Upload (multipart form; streamed to GridFS)
- `GET /files/public` — List public files (paged, optional `tag`, `sort`)
- `GET /files/me` — List my files (paged, optional `tag`, `sort`)
- `PATCH /files/{id}/rename` — Rename (owner only)
- `DELETE /files/{id}` — Delete (owner only)
- `GET /files/download/{token}` — Download by secure token (PUBLIC & PRIVATE)

> **Note:** The previous `/download/{token}` path has been **moved to** `/files/download/{token}` in this WebFlux version.

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
  tag: <string>   # repeat up to 5 times, e.g., tag=a&tag=b
```

**201 Created**
```json
{
  "id": "656d7f...",
  "download": "/files/download/AbCDefGhIjKlMnOpQrStUv"
}
```

### List (public)
```
GET /files/public?tag=invoices&sort=uploadedAt:desc&page=0&size=20
```
**200 OK** — JSON array of items for that page (each item: id, filename, size, contentType, visibility, tags, createdAt, …).

### List (mine)
```
GET /files/me?tag=reports&sort=filename:asc&page=0&size=10
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
GET /files/download/{token}
```
- Streams the file reactively.  
- `404` if token is invalid/expired or file missing.

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
java -jar build/libs/storage-stream-api-*.jar
```

Mongo default database must be reachable; **GridFS** buckets are created automatically.

---

## Docker

### Build (multi-stage)
```bash
docker build -t storage-stream-api:0.2.0 -f docker/Dockerfile .
```

### Run
```bash
docker run --rm -p 8080:8080   --memory=1g   --read-only   --tmpfs /tmp:rw,size=32m   -e MONGODB_URI='mongodb://host.docker.internal:27017/storage'   storage-stream-api:0.2.0
```

- No host volume mount is needed: files are streamed into **Mongo GridFS** (outside the image layer).

---

## Configuration
| Env Var        | Default                              | Description |
|----------------|--------------------------------------|-------------|
| `MONGODB_URI`  | `mongodb://localhost:27017/storage`  | Mongo connection string (used for metadata **and** GridFS) |
| `SERVER_PORT`  | `8080`                               | HTTP port |

---

## Testing

### What’s covered
1. **Parallel upload: same filename** (same user) → one succeeds, one `409`.
2. **Parallel upload: same content** (same user) → one succeeds, one `409`.
3. **Large upload (~2GB) simulation** via generated reactive stream; validates streaming & checksum without OOM.
4. **Delete not owner** → `403`.
5. **List public** and **list mine** with paging, sorting, tag filter.

Run:
```bash
./gradlew test
```

---

## Operational notes
- **User id** is provided with requests; the service exposes no user/session endpoints.
- **Content type detection** occurs **after upload** if omitted.
- **Download links** are **relative** to the service root, now under `/files/download/{token}`.

---

## Roadmap / Points of consideration
- **Scale out**: swap `ReactiveStoragePort` to S3/MinIO; enable **range requests**; shard Mongo; add read replicas and cache metadata hot paths; implement **rate limiting/backpressure**.
- **Monitoring**: Micrometer → Prometheus (RPS, p95/p99 latency, 4xx/5xx, storage usage, GridFS ops); Grafana dashboards & alerts.
- **Copy file**: metadata-only clone pointing to the same blob (ref-counted); mint a **new download token**; deleting one keeps the other until refcount is zero.
- **Persistence improvements**: content-addressable storage with periodic compaction and orphan cleanup; checksum validation on read.
- **Versioning**: per-file versions with distinct `gridFsId`/checksum/size/timestamps; “current” pointer on the root file id.
- **Resume upload**: chunked uploads (uploadId + parts + checksums), assemble on completion (TUS-like); ranged retries & integrity checks.

---

## License
MIT (or your preferred OSS license).
