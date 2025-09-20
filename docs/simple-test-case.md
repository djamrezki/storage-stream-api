
# Simple Test Case

This document demonstrates a minimal end-to-end usage of the **storage-api**.

```bash
BASE=http://localhost:8080
echo "hello world" > /tmp/hello.txt

# Upload a file
curl -v -X POST "$BASE/files"   -H "X-User-Id: u-demo"   -F "filename=hello.txt"   -F "visibility=PRIVATE"   -F "tag=demo"   -F "file=@/tmp/hello.txt"

# Example response:
# {"id":"68ceafc3fd02f61687a8913e","download":"/download/w8kFWfXPGoPymsrjXrniYBSJBJ4YtExw"}

# Download the file
curl -v -OJ "http://localhost:8080/download/w8kFWfXPGoPymsrjXrniYBSJBJ4YtExw"

# Verify contents
cat hello.txt
# should print: hello world

# List files (owned by user)
curl -s -H "X-User-Id: u-demo" "$BASE/files/me?page=0&size=10"

# List all files (admin scope)
curl -s -H "X-User-Id: u-demo" "$BASE/files/all?page=0&size=10"

# Attempt duplicate upload (should return 409 Conflict)
curl -v -H "X-User-Id: u-demo"   -F "filename=hello.txt" -F "visibility=PRIVATE" -F "tag=demo"   -F "file=@/tmp/hello.txt" $BASE/files

# Rename the file
curl -X PATCH "$BASE/files/68ceafc3fd02f61687a8913e/rename"   -H "X-User-Id: u-demo"   -H "Content-Type: application/json"   -d '{"filename":"hello-2.txt"}' -v

# Delete the file
curl -v -X DELETE -H "X-User-Id: u-demo" "$BASE/files/68ceafc3fd02f61687a8913e"
```
